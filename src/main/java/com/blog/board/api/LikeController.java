package com.blog.board.api;

import com.blog.board.service.LikeService;
import com.blog.board.service.LikeService.LikeResult;
import com.blog.common.security.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 좋아요 (T068, BRD-06). 다시 누르면 취소. */
@RestController
public class LikeController {

  private final LikeService likeService;

  public LikeController(LikeService likeService) {
    this.likeService = likeService;
  }

  @PostMapping("/api/posts/{postId}/like")
  public LikeResult toggle(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long postId,
      @RequestParam(required = false) String share) {
    return likeService.toggle(postId, user.id(), share);
  }
}
