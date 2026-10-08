package com.blog.common.security;

import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.BlogStatus;
import com.blog.blog.domain.ManagerPermission;
import com.blog.blog.repository.BlogManagerPermissionRepository;
import com.blog.blog.repository.BlogMemberRepository;
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
 *   <li>메인 관리자는 블로그 멤버가 되지 않으므로(D-90) 여기서는 늘 false. 관리자 기능은 {@code /api/admin/**}에서 따로 연다.
 * </ul>
 */
@Component("blogAuthz")
public class BlogAuthz {

  private final BlogMemberRepository memberRepository;
  private final BlogManagerPermissionRepository permissionRepository;
  private final Clock clock;

  public BlogAuthz(
      BlogMemberRepository memberRepository,
      BlogManagerPermissionRepository permissionRepository,
      Clock clock) {
    this.memberRepository = memberRepository;
    this.permissionRepository = permissionRepository;
    this.clock = clock;
  }

  /** 그 블로그의 멤버(블로그장·부블로그장 포함)이고, 그 블로그에서 정지되지 않았다. 멤버 전용 글·화면에 쓴다. */
  public boolean isMember(Long blogId) {
    return activeMembership(blogId).isPresent();
  }

  /** 블로그장이고 계정 정지 중이 아니다. 블로그 폐쇄·넘기기·부블로그장 지정처럼 블로그장만 하는 일에 쓴다. */
  public boolean isOwner(Long blogId) {
    return activeMembership(blogId).filter(BlogMember::isOwner).filter(this::canManage).isPresent();
  }

  /** 블로그장이거나, 그 권한을 받은 부블로그장이다 (BLG-13, D-71). */
  public boolean hasPermission(Long blogId, ManagerPermission permission) {
    return activeMembership(blogId).filter(m -> permits(m, permission)).isPresent();
  }

  /** SpEL에서 문자열로 부를 때: {@code @blogAuthz.hasPermission(#blogId, 'EDIT_INFO')} */
  public boolean hasPermission(Long blogId, String permission) {
    return hasPermission(blogId, ManagerPermission.valueOf(permission));
  }

  /**
   * 블로그 안 신고 처리(BLG-13). 블로그장, 또는 멤버 관리 권한을 받은 부블로그장. 단 블로그장이 계정 정지 중이면 그 기간의 신고는 메인 관리자가 처리하므로
   * 부블로그장도 false다 (D-100).
   */
  public boolean canHandleReports(Long blogId) {
    return activeMembership(blogId)
        .filter(m -> permits(m, ManagerPermission.MANAGE_MEMBERS))
        .filter(m -> !m.getBlog().getOwner().isSuspendedAt(now()))
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
    return member.isOwner()
        || (member.isManager()
            && permissionRepository.existsByBlogMemberIdAndPermission(member.getId(), permission));
  }

  /** 관리 기능은 계정 정지 중이 아닐 때만 (D-100, ADM-08). */
  private boolean canManage(BlogMember member) {
    return !member.getUser().isSuspendedAt(now());
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
