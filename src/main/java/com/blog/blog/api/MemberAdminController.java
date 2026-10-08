package com.blog.blog.api;

import com.blog.blog.domain.ManagerPermission;
import com.blog.blog.domain.MemberSanction;
import com.blog.blog.repository.MemberSanctionRepository;
import com.blog.blog.service.MemberAdminService;
import com.blog.blog.service.MemberAdminService.MemberRow;
import com.blog.blog.service.MemberSanctionService;
import com.blog.common.security.AuthUser;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 블로그 멤버 관리 (T076, T077, T081, T082, BLG-11, BLG-13, D-03, D-71). 멤버 목록·제재는 블로그장 또는 멤버 관리 권한 부블로그장
 * (블로그장 정지 중엔 모든 부블로그장, D-114), 부블로그장 지정은 블로그장만.
 */
@RestController
@RequestMapping("/api/blogs/{blogId}")
public class MemberAdminController {

  private final MemberAdminService adminService;
  private final MemberSanctionService sanctionService;
  private final MemberSanctionRepository sanctionRepository;

  public MemberAdminController(
      MemberAdminService adminService,
      MemberSanctionService sanctionService,
      MemberSanctionRepository sanctionRepository) {
    this.adminService = adminService;
    this.sanctionService = sanctionService;
    this.sanctionRepository = sanctionRepository;
  }

  /**
   * @param days 정지 기간 3·14·30. 영구 정지는 null
   */
  public record SanctionForm(String reason, Integer days) {}

  public record ManagerForm(boolean manager, Set<ManagerPermission> permissions) {}

  public record SanctionRow(
      Long id,
      String type,
      Integer suspendDays,
      LocalDateTime endsAt,
      String reason,
      String issuedBy,
      LocalDateTime releasedAt,
      LocalDateTime createdAt) {}

  @GetMapping("/members")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
  public List<MemberRow> members(@PathVariable Long blogId) {
    return adminService.members(blogId);
  }

  /** 한 멤버의 제재 이력 */
  @GetMapping("/members/{userId}/sanctions")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
  @Transactional(readOnly = true)
  public List<SanctionRow> sanctions(@PathVariable Long blogId, @PathVariable Long userId) {
    return sanctionRepository.findHistory(blogId, userId).stream().map(this::row).toList();
  }

  @PostMapping("/members/{userId}/warn")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
  public ResponseEntity<Void> warn(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @PathVariable Long userId,
      @RequestBody SanctionForm form) {
    sanctionService.warn(blogId, userId, user.id(), form.reason(), null);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/members/{userId}/suspend")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
  public ResponseEntity<Void> suspend(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @PathVariable Long userId,
      @RequestBody SanctionForm form) {
    sanctionService.suspend(blogId, userId, user.id(), form.days(), form.reason(), null);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/members/{userId}/kick")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
  public ResponseEntity<Void> kick(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @PathVariable Long userId,
      @RequestBody SanctionForm form) {
    sanctionService.kick(blogId, userId, user.id(), form.reason(), null);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/members/{userId}/release")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
  public ResponseEntity<Void> release(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @PathVariable Long userId) {
    sanctionService.release(blogId, userId, user.id());
    return ResponseEntity.noContent().build();
  }

  /** 부블로그장 지정·해제와 권한 체크박스 (T081). 블로그장만 */
  @PutMapping("/managers/{userId}")
  @PreAuthorize("@blogAuthz.isOwner(#blogId)")
  public ResponseEntity<Void> setManager(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @PathVariable Long userId,
      @RequestBody ManagerForm form) {
    adminService.setManager(blogId, userId, user.id(), form.manager(), form.permissions());
    return ResponseEntity.noContent().build();
  }

  private SanctionRow row(MemberSanction s) {
    return new SanctionRow(
        s.getId(),
        s.getType().name(),
        s.getSuspendDays(),
        s.getEndsAt(),
        s.getReason(),
        s.getIssuedBy().getNickname(),
        s.getReleasedAt(),
        s.getCreatedAt());
  }
}
