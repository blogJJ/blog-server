package com.blog.common.security;

import com.blog.auth.domain.UserRole;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.BlogStatus;
import com.blog.blog.domain.ManagerPermission;
import com.blog.blog.repository.BlogManagerPermissionRepository;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.blog.repository.BlogRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 블로그별 역할을 요청마다 DB로 확인한다 (T017, SEC-07, SEC-11, constitution II). 토큰에는 회원 번호만 있으므로 역할은 여기서만 판단한다.
 *
 * <p>컨트롤러나 서비스 메서드에 붙여 쓴다:
 *
 * <pre>{@code
 * @PreAuthorize("@blogAuthz.isOwner(#blogId)")
 * @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
 * }</pre>
 *
 * <p>공통 규칙:
 *
 * <ul>
 *   <li>비회원, 그 블로그 멤버가 아닌 사람, 폐쇄된(CLOSED) 블로그는 모두 false. 폐쇄 예정(CLOSING)은 평소처럼 운영한다 (D-67).
 *   <li>그 블로그에서 정지된 멤버(BLG-13)는 들어갈 수 없으므로 모든 확인이 false.
 *   <li>계정 정지(ADM-08) 중인 블로그장은 관리 기능을 쓸 수 없다 (D-100). 계정 정지 중인 부블로그장도 같게 막는다.
 *   <li>블로그장이 계정 정지 중이면 그동안 모든 부블로그장이 받은 체크박스와 관계없이 멤버 관리 권한(참여 승인, 멤버 제재, 신고 처리)을 임시로 갖는다 (D-114).
 *       따로 저장하지 않고 요청마다 블로그장의 정지 여부로 계산하므로 정지가 끝나면 바로 원래 권한으로 돌아간다.
 *   <li>메인 관리자(users.role = ADMIN)는 블로그 멤버가 되지 않지만(D-90) 폐쇄되지 않은 모든 블로그에서 블로그장의 관리 권한을 모두 갖는다
 *       (D-122). {@link #isOwner}, {@link #hasPermission}, {@link #canHandleReports}가 true다. 멤버
 *       자격({@link #isMember})과 블로그장 위임({@link #isRealOwner})은 주지 않는다. 역할은 요청마다 DB로 읽은 {@link
 *       AuthUser#role()}로 본다.
 * </ul>
 */
@Component("blogAuthz")
public class BlogAuthz {

  private final BlogRepository blogRepository;
  private final BlogMemberRepository memberRepository;
  private final BlogManagerPermissionRepository permissionRepository;
  private final Clock clock;

  public BlogAuthz(
      BlogRepository blogRepository,
      BlogMemberRepository memberRepository,
      BlogManagerPermissionRepository permissionRepository,
      Clock clock) {
    this.blogRepository = blogRepository;
    this.memberRepository = memberRepository;
    this.permissionRepository = permissionRepository;
    this.clock = clock;
  }

  /** 그 블로그의 멤버(블로그장·부블로그장 포함)이고, 그 블로그에서 정지되지 않았다. 멤버 전용 글·화면에 쓴다. */
  public boolean isMember(Long blogId) {
    return activeMembership(blogId).isPresent();
  }

  /**
   * 블로그장이고 계정 정지 중이 아니다. 블로그 폐쇄·철회, 부블로그장 지정처럼 블로그장만 하는 일에 쓴다. 메인 관리자도 true (D-122). 블로그장 위임은
   * {@link #isRealOwner}를 쓴다.
   */
  public boolean isOwner(Long blogId) {
    return actingAsAdmin(blogId) || isRealOwner(blogId);
  }

  /**
   * 그 블로그의 블로그장 멤버 본인이고 계정 정지 중이 아니다. 메인 관리자는 false. 블로그장 위임처럼 진짜 블로그장만 하는 일에 쓴다 (D-122). 관리자가 위임을
   * 요청하면 수락할 때 멤버가 아닌 관리자와 역할을 바꾸게 되기 때문이다.
   */
  public boolean isRealOwner(Long blogId) {
    return activeMembership(blogId).filter(BlogMember::isOwner).filter(this::canManage).isPresent();
  }

  /**
   * 블로그장이거나, 그 권한을 받은 부블로그장이다 (BLG-13, D-71). 블로그장 정지 중의 멤버 관리 권한은 D-114를 따른다. 메인 관리자는 모든 권한을 갖는다
   * (D-122).
   */
  public boolean hasPermission(Long blogId, ManagerPermission permission) {
    return actingAsAdmin(blogId)
        || activeMembership(blogId).filter(m -> permits(m, permission)).isPresent();
  }

  /** SpEL에서 문자열로 부를 때: {@code @blogAuthz.hasPermission(#blogId, 'EDIT_INFO')} */
  public boolean hasPermission(Long blogId, String permission) {
    return hasPermission(blogId, ManagerPermission.valueOf(permission));
  }

  /**
   * 블로그 안 신고 처리(BLG-13). 블로그장, 또는 멤버 관리 권한을 받은 부블로그장. 블로그장이 계정 정지 중이면 모든 부블로그장이 처리한다 (D-114). 블로그장
   * 본인 관련 신고처럼 메인 관리자가 맡는 신고는 신고의 {@code handler_scope}로 따로 거른다. 메인 관리자는 블로그 안 신고도 처리한다 (D-122).
   */
  public boolean canHandleReports(Long blogId) {
    return hasPermission(blogId, ManagerPermission.MANAGE_MEMBERS);
  }

  /** 지금 로그인한 회원이 메인 관리자인지. 역할은 요청마다 DB로 읽은 값이다. */
  public static boolean isAdmin() {
    return CurrentUser.get().map(AuthUser::role).filter(r -> r == UserRole.ADMIN).isPresent();
  }

  /** 메인 관리자이고 그 블로그가 있고 폐쇄되지 않았다 (D-122). */
  private boolean actingAsAdmin(Long blogId) {
    return blogId != null
        && isAdmin()
        && blogRepository
            .findById(blogId)
            .filter(b -> b.getStatus() != BlogStatus.CLOSED)
            .isPresent();
  }

  /** 지금 로그인한 회원의 그 블로그 멤버 행. 폐쇄된 블로그와 그 블로그에서 정지된 멤버는 비어 있다. */
  Optional<BlogMember> activeMembership(Long blogId) {
    if (blogId == null) {
      return Optional.empty();
    }
    LocalDateTime now = now();
    return CurrentUser.id()
        .flatMap(userId -> memberRepository.findForAuthz(blogId, userId))
        .filter(m -> m.getBlog().getStatus() != BlogStatus.CLOSED)
        .filter(m -> !m.isSuspendedAt(now));
  }

  private boolean permits(BlogMember member, ManagerPermission permission) {
    if (!canManage(member)) {
      return false;
    }
    if (member.isOwner()) {
      return true;
    }
    if (!member.isManager()) {
      return false;
    }
    if (permission == ManagerPermission.MANAGE_MEMBERS && ownerSuspended(member)) {
      return true;
    }
    return permissionRepository.existsByBlogMemberIdAndPermission(member.getId(), permission);
  }

  /** 블로그장이 계정 정지 중이다 (D-114). */
  private boolean ownerSuspended(BlogMember member) {
    return member.getBlog().getOwner().isSuspendedAt(now());
  }

  /** 관리 기능은 계정 정지 중이 아닐 때만 (D-100, ADM-08). */
  private boolean canManage(BlogMember member) {
    return !member.getUser().isSuspendedAt(now());
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
