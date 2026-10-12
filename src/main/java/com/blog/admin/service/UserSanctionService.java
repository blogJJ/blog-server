package com.blog.admin.service;

import com.blog.admin.domain.AdminAction;
import com.blog.admin.domain.AdminTargetType;
import com.blog.admin.domain.UserSanction;
import com.blog.admin.domain.UserSanctionType;
import com.blog.admin.repository.AdminQueryRepository;
import com.blog.admin.repository.UserSanctionRepository;
import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.board.service.ImageService;
import com.blog.common.domain.Suspensions;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 계정 경고·프로필 초기화·계정 정지·정지 해제 (T121, ADM-08, D-99, D-100).
 *
 * <ul>
 *   <li>프로필 초기화: 닉네임을 "회원"+회원 번호로, 사진·소개를 지운다. 사진 파일은 커밋한 뒤 지운다.
 *   <li>계정 정지: 3·14·30일·영구. {@code users.suspended_until}에 끝나는 시각(영구는 9999-12-31)을 넣는다. 정지된 회원은
 *       로그인과 읽기만 된다(AccountGuard). 블로그장이면 그 블로그들에 "블로그장 정지 중"이 뜨고 관리 기능이 막힌다(BlogAuthz, D-100,
 *       D-114).
 *   <li>영구 정지면 그 사람이 블로그장인 블로그마다 블로그장 권한 박탈(ADM-07)과 같이 처리한다.
 *   <li>해제: {@code suspended_until}을 비우고 마지막 정지 기록에 해제 시각을 남긴다.
 *   <li>메인 관리자 계정은 대상이 아니다. 대상에게 알리고(끌 수 없는 알림) 관리자 활동 기록을 남긴다.
 * </ul>
 */
@Service
public class UserSanctionService {

  private static final DateTimeFormatter UNTIL_FORMAT =
      DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");

  private final UserRepository userRepository;
  private final UserSanctionRepository sanctionRepository;
  private final AdminQueryRepository queryRepository;
  private final OwnerSanctionService ownerSanctionService;
  private final NotificationService notificationService;
  private final AdminActionLogger actionLogger;
  private final ImageService imageService;
  private final Clock clock;

  public UserSanctionService(
      UserRepository userRepository,
      UserSanctionRepository sanctionRepository,
      AdminQueryRepository queryRepository,
      OwnerSanctionService ownerSanctionService,
      NotificationService notificationService,
      AdminActionLogger actionLogger,
      ImageService imageService,
      Clock clock) {
    this.userRepository = userRepository;
    this.sanctionRepository = sanctionRepository;
    this.queryRepository = queryRepository;
    this.ownerSanctionService = ownerSanctionService;
    this.notificationService = notificationService;
    this.actionLogger = actionLogger;
    this.imageService = imageService;
    this.clock = clock;
  }

  /** 계정 경고 */
  @Transactional
  public void warn(Long userId, Long adminId, String reason, Long reportId) {
    String why = AdminReasons.valid(reason);
    User user = target(userId);
    save(user, UserSanctionType.WARNING, null, null, why, reportId, adminId);
    actionLogger.log(adminId, AdminAction.ACCOUNT_WARN, AdminTargetType.USER, userId, why);
    notifyUser(userId, adminId, "운영 정책에 따라 계정 경고를 받았어요. 사유: " + why);
  }

  /** 프로필 초기화 */
  @Transactional
  public void resetProfile(Long userId, Long adminId, String reason, Long reportId) {
    String why = AdminReasons.valid(reason);
    User user = target(userId);
    String nickname = "회원" + user.getId();
    if (!nickname.equals(user.getNickname()) && userRepository.existsByNickname(nickname)) {
      // "회원"은 예약어가 아니라 다른 회원이 이미 쓰고 있을 수 있다
      throw new BusinessException(
          ErrorCode.CONFLICT, "다른 회원이 닉네임 '" + nickname + "'을(를) 쓰고 있어 초기화할 수 없어요.");
    }
    String oldImage = user.resetProfile();
    save(user, UserSanctionType.PROFILE_RESET, null, null, why, reportId, adminId);
    actionLogger.log(adminId, AdminAction.PROFILE_RESET, AdminTargetType.USER, userId, why);
    notifyUser(
        userId,
        adminId,
        "운영 정책에 따라 프로필이 초기화되었어요 (닉네임 '" + user.getNickname() + "', 사진·소개 삭제). 사유: " + why);
    if (oldImage != null) {
      afterCommit(() -> imageService.deleteQuietly(oldImage));
    }
  }

  /**
   * 계정 정지.
   *
   * @param days 3·14·30, 영구는 null
   */
  @Transactional
  public void suspend(Long userId, Long adminId, Integer days, String reason, Long reportId) {
    String why = AdminReasons.valid(reason);
    AdminReasons.validDays(days);
    User user = target(userId);
    LocalDateTime until = days == null ? Suspensions.PERMANENT : now().plusDays(days);
    user.suspendAccount(until);
    save(user, UserSanctionType.SUSPENSION, days, until, why, reportId, adminId);
    actionLogger.log(adminId, AdminAction.ACCOUNT_SUSPEND, AdminTargetType.USER, userId, why);
    String period = days == null ? "영구" : until.format(UNTIL_FORMAT) + "까지";
    notifyUser(
        userId, adminId, "운영 정책에 따라 계정이 정지되었어요 (" + period + "). 정지 동안 읽기만 할 수 있어요. 사유: " + why);
    if (days == null) {
      // 영구 정지는 블로그장 강퇴와 같이 처리한다 (ADM-08, D-100)
      for (Blog blog : queryRepository.openBlogsOwnedBy(userId)) {
        ownerSanctionService.demote(blog.getId(), adminId, "계정 영구 정지. " + why, reportId);
      }
    }
  }

  /** 정지 해제 */
  @Transactional
  public void release(Long userId, Long adminId) {
    User user = target(userId);
    if (!user.isSuspendedAt(now())) {
      throw new BusinessException(ErrorCode.CONFLICT, "계정 정지 중인 회원이 아니에요.");
    }
    user.releaseAccountSuspension();
    User admin = userRepository.getReferenceById(adminId);
    sanctionRepository
        .findFirstByUserIdAndTypeOrderByIdDesc(userId, UserSanctionType.SUSPENSION)
        .filter(s -> s.getReleasedAt() == null)
        .ifPresent(s -> s.release(admin, now()));
    actionLogger.log(adminId, AdminAction.ACCOUNT_RELEASE, AdminTargetType.USER, userId, null);
    notifyUser(userId, adminId, "계정 정지가 풀렸어요.");
  }

  private User target(Long userId) {
    User user =
        userRepository
            .findById(userId)
            .filter(User::isActive)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (user.isAdmin()) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "관리자 계정은 제재할 수 없어요.");
    }
    return user;
  }

  private void save(
      User user,
      UserSanctionType type,
      Integer days,
      LocalDateTime endsAt,
      String reason,
      Long reportId,
      Long adminId) {
    sanctionRepository.save(
        new UserSanction(
            user, type, days, endsAt, reason, reportId, userRepository.getReferenceById(adminId)));
  }

  private void notifyUser(Long userId, Long adminId, String message) {
    notificationService.notify(
        userId,
        adminId,
        NotificationType.ACCOUNT_SANCTION,
        NotificationTargetType.USER,
        userId,
        message);
  }

  private static void afterCommit(Runnable task) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              task.run();
            }
          });
    } else {
      task.run();
    }
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
