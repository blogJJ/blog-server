package com.blog.admin.api;

import com.blog.admin.service.AdminUserService;
import com.blog.admin.service.AdminUserService.PrivateInfo;
import com.blog.admin.service.AdminUserService.UserDetail;
import com.blog.admin.service.AdminUserService.UserRow;
import com.blog.admin.service.UserSanctionService;
import com.blog.common.security.AuthUser;
import com.blog.common.web.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 관리 (T117, ADM-01, ADM-06, ADM-08). 주소 권한(ADMIN만)은 SecurityConfig가 본다. 이메일·전화번호는 가려서 주고, "전체
 * 보기"(reveal)만 원문을 주며 기록한다. 계정 경고·초기화·정지는 신고 처리(T123)로 하고, 여기서는 정지 해제만 한다.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

  private final AdminUserService userService;
  private final UserSanctionService sanctionService;

  public AdminUserController(AdminUserService userService, UserSanctionService sanctionService) {
    this.userService = userService;
    this.sanctionService = sanctionService;
  }

  @GetMapping
  public PageResponse<UserRow> search(
      @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page) {
    return userService.search(q, page);
  }

  @GetMapping("/{userId}")
  public UserDetail detail(@PathVariable Long userId) {
    return userService.detail(userId);
  }

  /** 이메일·전화번호 전체 보기. VIEW_PRIVATE_INFO로 기록한다 */
  @PostMapping("/{userId}/reveal")
  public PrivateInfo reveal(@AuthenticationPrincipal AuthUser admin, @PathVariable Long userId) {
    return userService.reveal(userId, admin.id());
  }

  /** 계정 정지 해제 (ADM-08) */
  @PostMapping("/{userId}/release")
  public ResponseEntity<Void> release(
      @AuthenticationPrincipal AuthUser admin, @PathVariable Long userId) {
    sanctionService.release(userId, admin.id());
    return ResponseEntity.noContent().build();
  }
}
