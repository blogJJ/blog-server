package com.blog.main.api;

import com.blog.common.security.AuthUser;
import com.blog.common.web.PageResponse;
import com.blog.main.service.FeedService;
import com.blog.main.service.PostCards.PostCard;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 메인 피드 (T106, BRD-09). 최신·인기는 비회원도, 팔로우·구독 탭은 로그인해야 본다. */
@RestController
public class FeedController {

  private final FeedService feedService;

  public FeedController(FeedService feedService) {
    this.feedService = feedService;
  }

  @GetMapping("/api/feed")
  public PageResponse<PostCard> feed(
      @AuthenticationPrincipal AuthUser user,
      @RequestParam(defaultValue = "latest") String tab,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(defaultValue = "1") int page) {
    return feedService.feed(tab, user == null ? null : user.id(), size, page);
  }
}
