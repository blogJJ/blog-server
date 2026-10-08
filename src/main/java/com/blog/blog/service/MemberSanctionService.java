package com.blog.blog.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogBlacklist;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.MemberSanction;
import com.blog.blog.domain.SanctionType;
import com.blog.blog.repository.BlogBlacklistRepository;
import com.blog.blog.repository.BlogManagerPermissionRepository;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.repository.MemberSanctionRepository;
import com.blog.board.repository.PostRepository;
import com.blog.common.crypto.HashUtil;
import com.blog.common.domain.Suspensions;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.BlogAuthz;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 안 멤버 경고·정지·정지 해제·강제 퇴장 (T076, T077, BLG-11, BLG-13, D-34, D-43, D-46, D-57).
 *
 * <ul>
 *   <li>권한(블로그장 또는 멤버 관리 권한 부블로그장, 블로그장 정지 중엔 모든 부블로그장)은 컨트롤러의 {@code @PreAuthorize}가 본다.
 *   <li>블로그장은 대상이 될 수 없다(블로그장 제재는 메인 관리자, ADM-07). 자기 자신도 안 된다. 부블로그장은 블로그장만 제재한다.
 *   <li>정지는 3·14·30일·영구(9999-12-31). 받을 때마다 정지 횟수가 1 오르고, 3번이면 관리 화면에 강제 퇴장 권유가 뜬다 (D-46).
 *   <li>강제 퇴장: 멤버에서 빼고, 그 블로그의 글을 "탈퇴한 계정"으로 바꾸고, 이름·이메일·전화번호 해시를 블랙리스트에 올린다.
 *   <li>모든 조치는 대상에게 알린다(끌 수 없는 알림).
 * </ul>
 */
@Service
public class MemberSanctionService {

  public static final int KICK_SUGGEST_AFTER = 3;
  private static final DateTimeFormatter UNTIL_FORMAT =
      DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");

  private final BlogRepository blogRepository;
  private final BlogMemberRepository memberRepository;
  private final BlogManagerPermissionRepository permissionRepository;
  private final MemberSanctionRepository sanctionRepository;
  private final BlogBlacklistRepository blacklistRepository;
  private final PostRepository postRepository;
  private final UserRepository userRepository;
  private final BlogAuthz blogAuthz;
  private final HashUtil hashUtil;
  private final NotificationService notificationService;
  private final Clock clock;

  public MemberSanctionService(
      BlogRepository blogRepository,
      BlogMemberRepository memberRepository,
      BlogManagerPermissionRepository permissionRepository,
      MemberSanctionRepository sanctionRepository,
      BlogBlacklistRepository blacklistRepository,
      PostRepository postRepository,
      UserRepository userRepository,
      BlogAuthz blogAuthz,
      HashUtil hashUtil,
      NotificationService notificationService,
      Clock clock) {
    this.blogRepository = blogRepository;
    this.memberRepository = memberRepository;
    this.permissionRepository = permissionRepository;
    this.sanctionRepository = sanctionRepository;
    this.blacklistRepository = blacklistRepository;
    this.postRepository = postRepository;
    this.userRepository = userRepository;
    this.blogAuthz = blogAuthz;
    this.hashUtil = hashUtil;
    this.notificationService = notificationService;
    this.clock = clock;
  }

  /**
   * 경고. 신고를 처리하며 주는 경고는 멤버가 아닌 회원(댓글을 단 방문자 등)에게도 줄 수 있다.
   *
   * @param reportId 신고 처리로 주면 그 신고, 아니면 null
   */
  @Transactional
  public MemberSanction warn(
      Long blogId, Long targetId, Long actorId, String reason, Long reportId) {
    Blog blog = openBlog(blogId);
    String why = validReason(reason);
    BlogMember member = memberRepository.findByBlogIdAndUserId(blogId, targetId).orElse(null);
    if (member == null && reportId == null) {
      throw notMember();
    }
    checkTarget(member, targetId, actorId);
    MemberSanction sanction =
        save(blog, targetId, SanctionType.WARNING, null, null, why, reportId, actorId);
    notifyTarget(blog, targetId, actorId, "'" + blog.getName() + "'에서 경고를 받았어요. 사유: " + why);
    return sanction;
  }

  /**
   * 정지. 그 블로그에만 못 들어간다 (D-43).
   *
   * @param days 3·14·30, 영구는 null
   */
  @Transactional
  public MemberSanction suspend(
      Long blogId, Long targetId, Long actorId, Integer days, String reason, Long reportId) {
    Blog blog = openBlog(blogId);
    String why = validReason(reason);
    if (days != null && !MemberSanction.SUSPEND_DAYS.contains(days)) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "정지 기간은 3일, 14일, 30일, 영구 중에서 골라 주세요.");
    }
    BlogMember member = member(blogId, targetId);
    checkTarget(member, targetId, actorId);
    LocalDateTime until = days == null ? Suspensions.PERMANENT : now().plusDays(days);
    member.suspend(until);
    MemberSanction sanction =
        save(blog, targetId, SanctionType.SUSPENSION, days, until, why, reportId, actorId);
    String period = days == null ? "영구" : until.format(UNTIL_FORMAT) + "까지";
    notifyTarget(
        blog, targetId, actorId, "'" + blog.getName() + "'에서 정지되었어요 (" + period + "). 사유: " + why);
    return sanction;
  }

  /** 정지 해제 */
  @Transactional
  public void release(Long blogId, Long targetId, Long actorId) {
    Blog blog = openBlog(blogId);
    BlogMember member = member(blogId, targetId);
    checkTarget(member, targetId, actorId);
    if (!member.isSuspendedAt(now())) {
      throw new BusinessException(ErrorCode.CONFLICT, "정지 중인 멤버가 아니에요.");
    }
    member.releaseSuspension();
    User actor = userRepository.getReferenceById(actorId);
    sanctionRepository
        .findFirstByBlogIdAndUserIdAndTypeOrderByIdDesc(blogId, targetId, SanctionType.SUSPENSION)
        .filter(s -> s.getReleasedAt() == null)
        .ifPresent(s -> s.release(actor, now()));
    notifyTarget(blog, targetId, actorId, "'" + blog.getName() + "' 정지가 풀렸어요.");
  }

  /** 강제 퇴장 (T077). 멤버에서 빼고 블랙리스트에 올린다 */
  @Transactional
  public MemberSanction kick(
      Long blogId, Long targetId, Long actorId, String reason, Long reportId) {
    Blog blog = openBlog(blogId);
    String why = validReason(reason);
    BlogMember member = member(blogId, targetId);
    checkTarget(member, targetId, actorId);
    User target = member.getUser();

    permissionRepository.deleteByBlogMemberId(member.getId());
    memberRepository.delete(member);
    memberRepository.flush();
    blogRepository.decreaseMemberCount(blogId);
    postRepository.hideAuthor(blogId, targetId);
    blacklistRepository.save(
        new BlogBlacklist(
            blog,
            target,
            hashUtil.hash(nullToEmpty(target.getName())),
            hashUtil.hash(nullToEmpty(User.normalizeEmail(target.getEmail()))),
            hashUtil.hashPhone(nullToEmpty(target.getPhone())),
            userRepository.getReferenceById(actorId)));
    MemberSanction sanction =
        save(blog, targetId, SanctionType.KICK, null, null, why, reportId, actorId);
    notifyTarget(blog, targetId, actorId, "'" + blog.getName() + "'에서 강제 퇴장되었어요. 사유: " + why);
    return sanction;
  }

  /**
   * 대상 확인. 블로그장과 자기 자신은 안 되고, 부블로그장은 블로그장만 제재한다.
   *
   * @param member 대상의 멤버 행. 멤버가 아니면 null
   */
  private void checkTarget(BlogMember member, Long targetId, Long actorId) {
    if (targetId.equals(actorId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "자기 자신은 제재할 수 없어요.");
    }
    if (member == null) {
      return;
    }
    if (member.isOwner()) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "블로그장은 제재할 수 없어요. 메인 관리자에게 신고해 주세요.");
    }
    if (member.isManager() && !blogAuthz.isOwner(member.getBlog().getId())) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "부블로그장은 블로그장만 제재할 수 있어요.");
    }
  }

  private MemberSanction save(
      Blog blog,
      Long targetId,
      SanctionType type,
      Integer days,
      LocalDateTime endsAt,
      String reason,
      Long reportId,
      Long actorId) {
    return sanctionRepository.save(
        new MemberSanction(
            blog,
            userRepository.getReferenceById(targetId),
            type,
            days,
            endsAt,
            reason,
            reportId,
            userRepository.getReferenceById(actorId)));
  }

  private void notifyTarget(Blog blog, Long targetId, Long actorId, String message) {
    notificationService.notify(
        targetId,
        actorId,
        NotificationType.MEMBER_SANCTION,
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

  private BlogMember member(Long blogId, Long userId) {
    return memberRepository.findByBlogIdAndUserId(blogId, userId).orElseThrow(this::notMember);
  }

  private BusinessException notMember() {
    return new BusinessException(ErrorCode.NOT_FOUND, "이 블로그의 멤버가 아니에요.");
  }

  static String validReason(String raw) {
    String reason = raw == null ? "" : raw.strip();
    int length = reason.codePointCount(0, reason.length());
    if (length < 1 || length > MemberSanction.REASON_MAX) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "사유를 1~500자로 입력해 주세요.");
    }
    return reason;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
