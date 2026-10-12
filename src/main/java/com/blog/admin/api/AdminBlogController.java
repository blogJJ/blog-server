package com.blog.admin.api;

import com.blog.admin.service.AdminBlogService;
import com.blog.admin.service.AdminBlogService.BlogRow;
import com.blog.admin.service.OwnerSanctionService;
import com.blog.common.security.AuthUser;
import com.blog.common.web.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 블로그 관리 (T118, ADM-02, ADM-07, D-96). 검색, 숨김·숨김 해제, 강제 폐쇄(7일 유예), 블로그장 경고·강퇴. 주소 권한(ADMIN만)은
 * SecurityConfig가 본다.
 */
@RestController
@RequestMapping("/api/admin/blogs")
public class AdminBlogController {

  /** 사유. 숨김은 선택, 폐쇄·블로그장 제재는 1~500자 */
  public record ReasonForm(String reason) {}

  private final AdminBlogService blogService;
  private final OwnerSanctionService ownerSanctionService;

  public AdminBlogController(
      AdminBlogService blogService, OwnerSanctionService ownerSanctionService) {
    this.blogService = blogService;
    this.ownerSanctionService = ownerSanctionService;
  }

  @GetMapping
  public PageResponse<BlogRow> search(
      @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page) {
    return blogService.search(q, page);
  }

  @PostMapping("/{blogId}/hide")
  public ResponseEntity<Void> hide(
      @AuthenticationPrincipal AuthUser admin,
      @PathVariable Long blogId,
      @RequestBody(required = false) ReasonForm form) {
    blogService.hide(blogId, admin.id(), reason(form));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{blogId}/unhide")
  public ResponseEntity<Void> unhide(
      @AuthenticationPrincipal AuthUser admin,
      @PathVariable Long blogId,
      @RequestBody(required = false) ReasonForm form) {
    blogService.unhide(blogId, admin.id(), reason(form));
    return ResponseEntity.noContent().build();
  }

  /** 강제 폐쇄: 누른 날짜 + 7일 04:00. 7일 안에 그 블로그의 블로그장이 철회할 수 있다 */
  @PostMapping("/{blogId}/close")
  public ResponseEntity<Void> close(
      @AuthenticationPrincipal AuthUser admin,
      @PathVariable Long blogId,
      @RequestBody ReasonForm form) {
    blogService.close(blogId, admin.id(), reason(form));
    return ResponseEntity.noContent().build();
  }

  /** 블로그장 경고. 그 블로그에서 최근 1년 안에 3번째면 권한 박탈 (ADM-07) */
  @PostMapping("/{blogId}/owner/warn")
  public ResponseEntity<Void> warnOwner(
      @AuthenticationPrincipal AuthUser admin,
      @PathVariable Long blogId,
      @RequestBody ReasonForm form) {
    ownerSanctionService.warn(blogId, admin.id(), reason(form), null);
    return ResponseEntity.noContent().build();
  }

  /** 블로그장 강퇴(권한 박탈) */
  @PostMapping("/{blogId}/owner/demote")
  public ResponseEntity<Void> demoteOwner(
      @AuthenticationPrincipal AuthUser admin,
      @PathVariable Long blogId,
      @RequestBody ReasonForm form) {
    ownerSanctionService.demote(blogId, admin.id(), reason(form), null);
    return ResponseEntity.noContent().build();
  }

  private static String reason(ReasonForm form) {
    return form == null ? null : form.reason();
  }
}
