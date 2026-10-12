package com.blog.blog.api;

import com.blog.admin.service.AdminBlogService;
import com.blog.auth.domain.UserRole;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogStatus;
import com.blog.blog.domain.CloseReason;
import com.blog.blog.service.BlogCloseService;
import com.blog.common.security.AuthUser;
import java.time.LocalDateTime;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 블로그 폐쇄·철회 (T088, BLG-09, D-12, D-19). 블로그장만(계정 정지 중이면 못 한다, D-100). 관리자 강제 폐쇄는 관리자 기능이 {@link
 * BlogCloseService}를 바로 부른다. 메인 관리자가 이 화면에서 누르면 관리자 강제 폐쇄(ADMIN)로 처리하고 활동 기록을 남긴다 (D-122).
 */
@RestController
@RequestMapping("/api/blogs/{blogId}/close")
public class BlogCloseController {

  private final BlogCloseService closeService;
  private final AdminBlogService adminBlogService;

  public BlogCloseController(BlogCloseService closeService, AdminBlogService adminBlogService) {
    this.closeService = closeService;
    this.adminBlogService = adminBlogService;
  }

  /**
   * @param closeScheduledAt 폐쇄 예정 시각. 철회했으면 null
   */
  public record CloseResponse(BlogStatus status, LocalDateTime closeScheduledAt) {

    static CloseResponse of(Blog blog) {
      return new CloseResponse(blog.getStatus(), blog.getCloseScheduledAt());
    }
  }

  /** 폐쇄 버튼: 누른 날짜 + 7일 04:00에 폐쇄 예정 */
  @PostMapping
  @PreAuthorize("@blogAuthz.isOwner(#blogId)")
  public CloseResponse close(@AuthenticationPrincipal AuthUser user, @PathVariable Long blogId) {
    if (user.role() == UserRole.ADMIN) {
      return CloseResponse.of(adminBlogService.closeFromBlogPage(blogId, user.id()));
    }
    return CloseResponse.of(closeService.scheduleClose(blogId, CloseReason.OWNER, user.id()));
  }

  /** 폐쇄 철회 */
  @PostMapping("/revoke")
  @PreAuthorize("@blogAuthz.isOwner(#blogId)")
  public CloseResponse revoke(@AuthenticationPrincipal AuthUser user, @PathVariable Long blogId) {
    if (user.role() == UserRole.ADMIN) {
      return CloseResponse.of(adminBlogService.revokeCloseFromBlogPage(blogId, user.id()));
    }
    return CloseResponse.of(closeService.revokeClose(blogId, user.id()));
  }
}
