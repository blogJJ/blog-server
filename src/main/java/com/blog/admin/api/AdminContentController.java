package com.blog.admin.api;

import com.blog.admin.service.AdminContentService;
import com.blog.admin.service.AdminContentService.CommentRow;
import com.blog.admin.service.AdminContentService.PostRow;
import com.blog.common.security.AuthUser;
import com.blog.common.web.PageResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 글·댓글 숨김·삭제와 30일 안 글 복구 (T119, ADM-03, 4.5). 사유는 선택이고 활동 기록과 삭제 알림에 들어간다. */
@RestController
@RequestMapping("/api/admin")
public class AdminContentController {

  public record ReasonForm(String reason) {}

  private final AdminContentService contentService;

  public AdminContentController(AdminContentService contentService) {
    this.contentService = contentService;
  }

  /** 제목으로 글 찾기 (숨김·삭제 포함) */
  @GetMapping("/posts")
  public PageResponse<PostRow> posts(
      @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page) {
    return contentService.searchPosts(q, page);
  }

  @GetMapping("/posts/{postId}/comments")
  public List<CommentRow> comments(@PathVariable Long postId) {
    return contentService.comments(postId);
  }

  @PostMapping("/posts/{postId}/hide")
  public ResponseEntity<Void> hidePost(
      @AuthenticationPrincipal AuthUser admin,
      @PathVariable Long postId,
      @RequestBody(required = false) ReasonForm form) {
    contentService.hidePost(postId, admin.id(), reason(form));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/posts/{postId}/delete")
  public ResponseEntity<Void> deletePost(
      @AuthenticationPrincipal AuthUser admin,
      @PathVariable Long postId,
      @RequestBody(required = false) ReasonForm form) {
    contentService.deletePost(postId, admin.id(), reason(form));
    return ResponseEntity.noContent().build();
  }

  /** 숨긴 글이나 삭제한 지 30일 안의 글을 다시 게시 */
  @PostMapping("/posts/{postId}/restore")
  public ResponseEntity<Void> restorePost(
      @AuthenticationPrincipal AuthUser admin,
      @PathVariable Long postId,
      @RequestBody(required = false) ReasonForm form) {
    contentService.restorePost(postId, admin.id(), reason(form));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/comments/{commentId}/hide")
  public ResponseEntity<Void> hideComment(
      @AuthenticationPrincipal AuthUser admin,
      @PathVariable Long commentId,
      @RequestBody(required = false) ReasonForm form) {
    contentService.hideComment(commentId, admin.id(), reason(form));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/comments/{commentId}/delete")
  public ResponseEntity<Void> deleteComment(
      @AuthenticationPrincipal AuthUser admin,
      @PathVariable Long commentId,
      @RequestBody(required = false) ReasonForm form) {
    contentService.deleteComment(commentId, admin.id(), reason(form));
    return ResponseEntity.noContent().build();
  }

  private static String reason(ReasonForm form) {
    return form == null ? null : form.reason();
  }
}
