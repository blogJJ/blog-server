package com.blog.board.api;

import com.blog.board.service.CommentModerationService;
import com.blog.board.service.CommentModerationService.HeldRow;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 블로그 관리의 "댓글 확인" (T166, D-133). 자동 검사가 숨긴 댓글을 블로그장·글 관리 권한 부블로그장·메인 관리자가 승인하거나 지운다. */
@RestController
@RequestMapping("/api/blogs/{blogId}/comment-reviews")
public class CommentReviewController {

  private final CommentModerationService moderationService;

  public CommentReviewController(CommentModerationService moderationService) {
    this.moderationService = moderationService;
  }

  @GetMapping
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_POSTS')")
  public List<HeldRow> held(@PathVariable Long blogId) {
    return moderationService.held(blogId);
  }

  @PostMapping("/{commentId}/approve")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_POSTS')")
  public ResponseEntity<Void> approve(@PathVariable Long blogId, @PathVariable Long commentId) {
    moderationService.approve(blogId, commentId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{commentId}")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_POSTS')")
  public ResponseEntity<Void> delete(@PathVariable Long blogId, @PathVariable Long commentId) {
    moderationService.delete(blogId, commentId);
    return ResponseEntity.noContent().build();
  }
}
