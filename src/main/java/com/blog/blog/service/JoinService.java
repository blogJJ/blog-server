package com.blog.blog.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogJoinRequest;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.BlogMemberRole;
import com.blog.blog.domain.JoinPolicy;
import com.blog.blog.domain.JoinRequestStatus;
import com.blog.blog.domain.ManagerPermission;
import com.blog.blog.repository.BlogJoinRequestRepository;
import com.blog.blog.repository.BlogManagerPermissionRepository;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.blog.repository.BlogRepository;
import com.blog.common.domain.Suspensions;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.AccountGuard;
import com.blog.common.security.AccountGuard.Activity;
import com.blog.social.repository.UserBlockRepository;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 참여 신청·승인·거절·취소 (T050, T051, BLG-04, BLG-05, D-01, D-115).
 *
 * <ul>
 *   <li>자유 참여는 바로 MEMBER, 승인제는 PENDING 신청을 만든다.
 *   <li>대기 중인 신청이 있으면 다시 신청할 수 없고, 거절되면 처리 시각부터 7일 뒤에 다시 신청할 수 있다.
 *   <li>블로그를 볼 수 있어야 신청할 수 있다. 일부 공개는 공유 링크로 들어온 사람만, 비공개는 신청할 수 없다.
 *   <li>블로그장이 계정 정지 중이고 부블로그장이 없으면 정지가 끝날 때까지 받지 않는다 (D-115). 자유 참여도 같다.
 *   <li>그 블로그 블랙리스트(강제 퇴장 기록)의 이메일이나 전화번호 해시와 같으면 재가입했어도 거절한다 (T078, BLG-11).
 *   <li>승인제 신청은 블로그장과 멤버 관리 권한이 있는 부블로그장에게 알린다. 블로그장 정지 중에는 모든 부블로그장에게 (D-114).
 * </ul>
 */
@Service
public class JoinService {

  public static final Duration REAPPLY_AFTER_REJECT = Duration.ofDays(7);
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy년 M월 d일");
  private static final DateTimeFormatter DATE_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");

  /** 신청 결과. joined면 바로 멤버가 됐고, 아니면 request가 대기 중이다. */
  public record JoinResult(boolean joined, BlogJoinRequest request) {}

  private final BlogRepository blogRepository;
  private final BlogMemberRepository memberRepository;
  private final BlogManagerPermissionRepository permissionRepository;
  private final BlogJoinRequestRepository requestRepository;
  private final UserRepository userRepository;
  private final BlogAccessService accessService;
  private final AccountGuard accountGuard;
  private final NotificationService notificationService;
  private final BlacklistService blacklistService;
  private final UserBlockRepository blockRepository;
  private final Clock clock;

  public JoinService(
      BlogRepository blogRepository,
      BlogMemberRepository memberRepository,
      BlogManagerPermissionRepository permissionRepository,
      BlogJoinRequestRepository requestRepository,
      UserRepository userRepository,
      BlogAccessService accessService,
      AccountGuard accountGuard,
      NotificationService notificationService,
      BlacklistService blacklistService,
      UserBlockRepository blockRepository,
      Clock clock) {
    this.blogRepository = blogRepository;
    this.memberRepository = memberRepository;
    this.permissionRepository = permissionRepository;
    this.requestRepository = requestRepository;
    this.userRepository = userRepository;
    this.accessService = accessService;
    this.accountGuard = accountGuard;
    this.notificationService = notificationService;
    this.blacklistService = blacklistService;
    this.blockRepository = blockRepository;
    this.clock = clock;
  }

  /** 참여 신청 (T050). */
  @Transactional
  public JoinResult join(Long blogId, Long userId, String shareKey) {
    accountGuard.check(userId, Activity.JOIN_BLOG);
    Blog blog =
        blogRepository
            .findById(blogId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    BlogAccessService.Access access = accessService.check(blog, userId, shareKey);
    if (access.membership().isPresent()) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 이 블로그의 멤버예요.");
    }
    if (blacklistService.isBlacklisted(blogId, userId)) {
      throw new BusinessException(ErrorCode.BLACKLISTED);
    }
    if (blockRepository.existsByBlockerIdAndBlockedId(blog.getOwner().getId(), userId)) {
      // 블로그장이 차단한 회원의 신청은 자동 거절 (SOC-05, D-36)
      throw new BusinessException(ErrorCode.FORBIDDEN, "이 블로그에는 참여 신청을 할 수 없어요. 신청이 자동으로 거절되었어요.");
    }
    LocalDateTime now = now();
    List<BlogMember> managers = memberRepository.findManagers(blogId);
    User owner = blog.getOwner();
    boolean ownerSuspended = owner.isSuspendedAt(now);
    if (ownerSuspended && managers.isEmpty()) {
      throw new BusinessException(
          ErrorCode.FORBIDDEN, "블로그장 정지로 참여 신청이 일시 중지되었어요 (" + untilText(owner) + "까지)");
    }

    BlogJoinRequest last =
        requestRepository.findFirstByBlogIdAndUserIdOrderByIdDesc(blogId, userId).orElse(null);
    if (last != null && last.isPending()) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 참여 신청을 했어요. 승인을 기다려 주세요.");
    }
    User user = userRepository.getReferenceById(userId);

    if (blog.getJoinPolicy() == JoinPolicy.OPEN) {
      addMember(blog, user);
      return new JoinResult(true, null);
    }

    if (last != null && last.getStatus() == JoinRequestStatus.REJECTED) {
      LocalDateTime reapplyAt = last.getHandledAt().plus(REAPPLY_AFTER_REJECT);
      if (now.isBefore(reapplyAt)) {
        throw new BusinessException(
            ErrorCode.CONFLICT,
            "참여 신청이 거절되어 " + reapplyAt.format(DATE_TIME_FORMAT) + " 이후에 다시 신청할 수 있어요.");
      }
    }
    BlogJoinRequest request = requestRepository.save(new BlogJoinRequest(blog, user));

    List<Long> receivers = new ArrayList<>();
    if (!ownerSuspended) {
      receivers.add(owner.getId());
    }
    for (BlogMember manager : managers) {
      if (ownerSuspended
          || permissionRepository.existsByBlogMemberIdAndPermission(
              manager.getId(), ManagerPermission.MANAGE_MEMBERS)) {
        receivers.add(manager.getUser().getId());
      }
    }
    notificationService.notifyAll(
        receivers,
        userId,
        NotificationType.JOIN_REQUEST,
        NotificationTargetType.BLOG,
        blogId,
        "'" + blog.getName() + "'에 참여 신청이 왔어요.");
    return new JoinResult(false, request);
  }

  /** 승인 (T051). 권한은 컨트롤러가 확인한다. */
  @Transactional
  public void approve(Long blogId, Long requestId, Long handlerId) {
    BlogJoinRequest request = pendingRequest(blogId, requestId);
    Blog blog = request.getBlog();
    if (!blog.isOpen()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    request.approve(userRepository.getReferenceById(handlerId), now());
    if (!memberRepository.existsByBlogIdAndUserId(blogId, request.getUser().getId())) {
      addMember(blog, request.getUser());
    }
    notifyResult(request, handlerId, "'" + blog.getName() + "' 참여 신청이 승인되었어요.");
  }

  /** 거절 (T051). 7일 뒤 다시 신청할 수 있다. */
  @Transactional
  public void reject(Long blogId, Long requestId, Long handlerId) {
    BlogJoinRequest request = pendingRequest(blogId, requestId);
    request.reject(userRepository.getReferenceById(handlerId), now());
    notifyResult(
        request,
        handlerId,
        "'" + request.getBlog().getName() + "' 참여 신청이 거절되었어요. 7일 뒤 다시 신청할 수 있어요.");
  }

  /** 신청한 사람이 취소한다 (T051). */
  @Transactional
  public void cancel(Long blogId, Long requestId, Long userId) {
    BlogJoinRequest request = pendingRequest(blogId, requestId);
    if (!request.getUser().getId().equals(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    request.cancel(now());
  }

  /** 관리 화면의 대기 신청 목록. 권한은 컨트롤러가 확인한다. */
  @Transactional(readOnly = true)
  public List<BlogJoinRequest> pending(Long blogId) {
    return requestRepository.findWithUserByBlogIdAndStatus(blogId, JoinRequestStatus.PENDING);
  }

  /** 블로그 화면의 버튼 상태용. 이 회원의 가장 최근 신청. */
  @Transactional(readOnly = true)
  public BlogJoinRequest latest(Long blogId, Long userId) {
    return requestRepository.findFirstByBlogIdAndUserIdOrderByIdDesc(blogId, userId).orElse(null);
  }

  private BlogJoinRequest pendingRequest(Long blogId, Long requestId) {
    BlogJoinRequest request =
        requestRepository
            .findById(requestId)
            .filter(r -> r.getBlog().getId().equals(blogId))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (!request.isPending()) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 처리된 신청이에요.");
    }
    return request;
  }

  private void addMember(Blog blog, User user) {
    try {
      memberRepository.saveAndFlush(new BlogMember(blog, user, BlogMemberRole.MEMBER));
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 이 블로그의 멤버예요.");
    }
    blogRepository.increaseMemberCount(blog.getId());
  }

  private void notifyResult(BlogJoinRequest request, Long handlerId, String message) {
    notificationService.notify(
        request.getUser().getId(),
        handlerId,
        NotificationType.JOIN_RESULT,
        NotificationTargetType.BLOG,
        request.getBlog().getId(),
        message);
  }

  private static String untilText(User owner) {
    LocalDateTime until = owner.getSuspendedUntil();
    return !until.isBefore(Suspensions.PERMANENT) ? "정지가 풀릴 때" : until.format(DATE_FORMAT);
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
