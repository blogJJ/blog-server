package com.blog.board.api;

import com.blog.board.domain.Comment;
import com.blog.board.service.CommentService;
import com.blog.board.service.CommentService.CommentForm;
import com.blog.board.service.CommentService.CommentRow;
import com.blog.common.security.AuthUser;
import com.blog.common.web.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 댓글·대댓글 (T067, BRD-06). */
@RestController
public class CommentController {

  private final CommentService commentService;

  public CommentController(CommentService commentService) {
    this.commentService = commentService;
  }

  public record Saved(Long id) {}

  @GetMapping("/api/posts/{postId}/comments")
  public PageResponse<CommentRow> list(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long postId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) String share) {
    return commentService.list(
        postId, user == null ? null : user.id(), user == null ? null : user.role(), share, page);
  }

  /** 일부 공개 블로그는 share 값을 같이 보낸다 */
  @PostMapping("/api/posts/{postId}/comments")
  @ResponseStatus(HttpStatus.CREATED)
  public Saved create(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long postId,
      @RequestParam(required = false) String share,
      @RequestBody CommentForm form) {
    Comment comment = commentService.create(postId, user.id(), share, form);
    return new Saved(comment.getId());
  }

  @PutMapping("/api/comments/{commentId}")
  public Saved update(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long commentId,
      @RequestBody CommentForm form) {
    return new Saved(commentService.update(commentId, user.id(), form).getId());
  }

  @DeleteMapping("/api/comments/{commentId}")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal AuthUser user, @PathVariable Long commentId) {
    commentService.delete(commentId, user.id(), user.role());
    return ResponseEntity.noContent().build();
  }
}
