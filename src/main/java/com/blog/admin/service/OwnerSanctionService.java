package com.blog.admin.service;

import com.blog.admin.domain.AdminAction;
import com.blog.admin.domain.AdminTargetType;
import com.blog.admin.domain.OwnerSanction;
import com.blog.admin.domain.OwnerSanctionType;
import com.blog.admin.repository.AdminQueryRepository;
import com.blog.admin.repository.OwnerSanctionRepository;
import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.CloseReason;
import com.blog.blog.repository.BlogManagerPermissionRepository;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.service.BlogCloseService;
import com.blog.blog.service.TransferService;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그장 경고·권한 박탈 (T120, ADM-07, D-30, D-54, D-97).
 *
 * <ul>
 *   <li>경고는 블로그별로 세고 1년 지난 경고는 뺀다. 그 블로그에서 최근 1년 안에 3번째 경고면 바로 권한을 박탈한다.
 *   <li>박탈: 블로그장은 일반 멤버로 남고(D-54), 가장 먼저 부블로그장이 된 사람(manager_since)이 블로그장이 된다. 새 블로그장의 부블로그장 권한
 *       체크박스는 지운다(블로그장은 모든 권한을 가진다).
 *   <li>부블로그장이 없으면 BlogCloseService로 7일 유예 후 폐쇄 예정({@code close_reason} OWNER_DEMOTED)으로 바꾸고,
 *       멤버·구독자에게 "블로그장 권한 박탈로 인한 폐쇄 조치" 알림이 간다. 이미 폐쇄 예정이면 그대로 둔다. 이때 {@code blogs.owner_id}는 NOT
 *       NULL이라 박탈된 사람으로 남지만 멤버 역할이 MEMBER라 관리 기능은 쓸 수 없다.
 *   <li>블로그장에게 알리고(끌 수 없는 알림), 모든 조치를 관리자 활동 기록에 남긴다.
 * </ul>
 */
@Service
public class OwnerSanctionService {

  /** 이 횟수가 되면 박탈 (ADM-07) */
  public static final int DEMOTE_AFTER_WARNINGS = 3;

  /** 박탈로 이어졌는지 */
  public enum Outcome {
    WARNED,
    DEMOTED
  }

  private final BlogRepository blogRepository;
  private final BlogMemberRepository memberRepository;
  private final BlogManagerPermissionRepository permissionRepository;
  private final OwnerSanctionRepository sanctionRepository;
  private final AdminQueryRepository queryRepository;
  private final UserRepository userRepository;
  private final BlogCloseService closeService;
  private final TransferService transferService;
  private final NotificationService notificationService;
  private final AdminActionLogger actionLogger;
  private final Clock clock;

  public OwnerSanctionService(
      BlogRepository blogRepository,
      BlogMemberRepository memberRepository,
      BlogManagerPermissionRepository permissionRepository,
      OwnerSanctionRepository sanctionRepository,
      AdminQueryRepository queryRepository,
      UserRepository userRepository,
      BlogCloseService closeService,
      TransferService transferService,
      NotificationService notificationService,
      AdminActionLogger actionLogger,
      Clock clock) {
    this.blogRepository = blogRepository;
    this.memberRepository = memberRepository;
    this.permissionRepository = permissionRepository;
    this.sanctionRepository = sanctionRepository;
    this.queryRepository = queryRepository;
    this.userRepository = userRepository;
    this.closeService = closeService;
    this.transferService = transferService;
    this.notificationService = notificationService;
    this.actionLogger = actionLogger;
    this.clock = clock;
  }

  /**
   * 블로그장 경고. 그 블로그에서 최근 1년 안에 3번째면 바로 박탈한다.
   *
   * @param reportId 신고를 처리하며 주면 그 신고, 아니면 null
   */
  @Transactional
  public Outcome warn(Long blogId, Long adminId, String reason, Long reportId) {
    String why = AdminReasons.valid(reason);
    Blog blog = openBlog(blogId);
    User owner = blog.getOwner();
    ownerMembership(blog);
    sanctionRepository.save(
        new OwnerSanction(
            blog,
            owner,
            OwnerSanctionType.WARNING,
            why,
            reportId,
            userRepository.getReferenceById(adminId)));
    sanctionRepository.flush();
    long warnings =
        sanctionRepository.countByBlogIdAndUserIdAndTypeAndCreatedAtAfter(
            blogId, owner.getId(), OwnerSanctionType.WARNING, now().minusYears(1));
    actionLogger.log(adminId, AdminAction.OWNER_WARN, AdminTargetType.BLOG, blogId, why);
    notifyOwner(
        blog,
        owner.getId(),
        adminId,
        "'"
            + blog.getName()
            + "' 블로그장 경고를 받았어요 (최근 1년 "
            + warnings
            + "/"
            + DEMOTE_AFTER_WARNINGS
            + "회). 사유: "
            + why);
    if (warnings >= DEMOTE_AFTER_WARNINGS) {
      demoteNow(blog, adminId, "최근 1년 안에 블로그장 경고 " + DEMOTE_AFTER_WARNINGS + "회. " + why, reportId);
      return Outcome.DEMOTED;
    }
    return Outcome.WARNED;
  }

  /** 블로그장 권한 박탈(강퇴). */
  @Transactional
  public void demote(Long blogId, Long adminId, String reason, Long reportId) {
    demoteNow(openBlog(blogId), adminId, AdminReasons.valid(reason), reportId);
  }

  private void demoteNow(Blog blog, Long adminId, String reason, Long reportId) {
    User owner = blog.getOwner();
    // 박탈된 블로그장이 보낸 위임 요청은 더 수락할 수 없으므로 함께 끝낸다 (D-145)
    transferService.cancelForDemotedOwner(blog.getId(), adminId);
    BlogMember ownerMember = ownerMembership(blog);
    sanctionRepository.save(
        new OwnerSanction(
            blog,
            owner,
            OwnerSanctionType.DEMOTION,
            AdminReasons.valid(cut(reason)),
            reportId,
            userRepository.getReferenceById(adminId)));

    List<BlogMember> managers = queryRepository.managersByTenure(blog.getId());
    String followUp;
    if (!managers.isEmpty()) {
      BlogMember successor = managers.get(0);
      permissionRepository.deleteByBlogMemberId(successor.getId());
      ownerMember.demoteFromOwner();
      successor.promoteToOwner();
      blog.changeOwner(successor.getUser());
      notificationService.notify(
          successor.getUser().getId(),
          adminId,
          NotificationType.OWNER_TRANSFER_RESULT,
          NotificationTargetType.BLOG,
          blog.getId(),
          "'" + blog.getName() + "' 블로그장의 권한이 박탈되어 블로그장이 되었어요. 박탈된 블로그장은 일반 멤버로 남아요.");
      followUp = " 블로그장은 " + successor.getUser().getNickname() + "님에게 넘어갔어요.";
    } else {
      ownerMember.demoteFromOwner();
      if (blog.isActive()) {
        closeService.scheduleClose(blog.getId(), CloseReason.OWNER_DEMOTED, adminId);
      }
      followUp = " 부블로그장이 없어 블로그는 7일 뒤 폐쇄돼요.";
    }
    actionLogger.log(adminId, AdminAction.OWNER_DEMOTE, AdminTargetType.BLOG, blog.getId(), reason);
    notifyOwner(
        blog,
        owner.getId(),
        adminId,
        "'" + blog.getName() + "' 블로그장 권한이 박탈되어 일반 멤버가 되었어요." + followUp + " 사유: " + reason);
  }

  private BlogMember ownerMembership(Blog blog) {
    return memberRepository
        .findByBlogIdAndUserId(blog.getId(), blog.getOwner().getId())
        .filter(BlogMember::isOwner)
        .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "이미 블로그장 권한이 박탈된 블로그예요."));
  }

  private void notifyOwner(Blog blog, Long ownerId, Long adminId, String message) {
    notificationService.notify(
        ownerId,
        adminId,
        NotificationType.OWNER_SANCTION,
        NotificationTargetType.BLOG,
        blog.getId(),
        message);
  }

  private Blog openBlog(Long blogId) {
    return blogRepository
        .findById(blogId)
        .filter(Blog::isOpen)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static String cut(String reason) {
    return reason.length() <= AdminReasons.REASON_MAX
        ? reason
        : reason.substring(0, AdminReasons.REASON_MAX);
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
