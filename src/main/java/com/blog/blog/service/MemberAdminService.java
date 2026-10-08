package com.blog.blog.service;

import com.blog.auth.domain.User;
import com.blog.blog.domain.BlogManagerPermission;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.ManagerPermission;
import com.blog.blog.repository.BlogManagerPermissionRepository;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.privacy.Masking;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멤버 목록과 부블로그장 지정·해제 (T081, T082, D-03, D-71, 4.4).
 *
 * <ul>
 *   <li>목록은 블로그장·멤버 관리 권한 부블로그장만(컨트롤러). 이메일·전화번호는 가려서 준다.
 *   <li>부블로그장 지정·해제와 권한 체크박스는 블로그장만(컨트롤러). 블로그장이 계정 정지 중이면 못 한다 (D-114).
 * </ul>
 */
@Service
public class MemberAdminService {

  public record MemberRow(
      Long userId,
      String nickname,
      String email,
      String phone,
      String role,
      List<ManagerPermission> permissions,
      LocalDateTime joinedAt,
      LocalDateTime suspendedUntil,
      int suspensionCount,
      boolean kickSuggested) {}

  private final BlogMemberRepository memberRepository;
  private final BlogManagerPermissionRepository permissionRepository;
  private final Clock clock;

  public MemberAdminService(
      BlogMemberRepository memberRepository,
      BlogManagerPermissionRepository permissionRepository,
      Clock clock) {
    this.memberRepository = memberRepository;
    this.permissionRepository = permissionRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<MemberRow> members(Long blogId) {
    List<BlogMember> members = memberRepository.findForAdmin(blogId);
    Map<Long, List<ManagerPermission>> permissions =
        permissionRepository
            .findByBlogMemberIdIn(members.stream().map(BlogMember::getId).toList())
            .stream()
            .collect(
                Collectors.groupingBy(
                    p -> p.getBlogMember().getId(),
                    Collectors.mapping(BlogManagerPermission::getPermission, Collectors.toList())));
    LocalDateTime now = LocalDateTime.now(clock);
    return members.stream()
        .map(
            m -> {
              User u = m.getUser();
              return new MemberRow(
                  u.getId(),
                  u.isActive() && u.getNickname() != null ? u.getNickname() : "탈퇴한 회원",
                  Masking.email(u.getEmail()),
                  Masking.phone(u.getPhone()),
                  m.getRole().name(),
                  permissions.getOrDefault(m.getId(), List.of()),
                  m.getJoinedAt(),
                  m.isSuspendedAt(now) ? m.getSuspendedUntil() : null,
                  m.getSuspensionCount(),
                  m.getSuspensionCount() >= MemberSanctionService.KICK_SUGGEST_AFTER);
            })
        .toList();
  }

  /**
   * 부블로그장 지정·해제 (T081). manager가 false면 멤버로 내리고 권한을 모두 지운다.
   *
   * @param permissions 부블로그장에게 줄 권한. 비어 있어도 된다
   */
  @Transactional
  public void setManager(
      Long blogId,
      Long targetId,
      Long actorId,
      boolean manager,
      Set<ManagerPermission> permissions) {
    if (targetId.equals(actorId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "자기 자신의 역할은 바꿀 수 없어요.");
    }
    BlogMember member =
        memberRepository
            .findByBlogIdAndUserId(blogId, targetId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이 블로그의 멤버가 아니에요."));
    if (member.isOwner()) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "블로그장의 역할은 바꿀 수 없어요.");
    }
    permissionRepository.deleteByBlogMemberId(member.getId());
    permissionRepository.flush();
    if (!manager) {
      member.demoteToMember();
      return;
    }
    if (!member.isManager()) {
      member.promoteToManager(LocalDateTime.now(clock));
    }
    Set<ManagerPermission> granted =
        permissions == null || permissions.isEmpty()
            ? EnumSet.noneOf(ManagerPermission.class)
            : EnumSet.copyOf(permissions);
    for (ManagerPermission p : granted) {
      permissionRepository.save(new BlogManagerPermission(member, p));
    }
  }
}
