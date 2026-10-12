package com.blog.social.api;

import com.blog.common.security.AuthUser;
import com.blog.common.web.PageResponse;
import com.blog.social.service.FollowService;
import com.blog.social.service.FollowService.FollowResult;
import com.blog.social.service.FollowService.SubscribeResult;
import com.blog.social.service.FollowService.UserRow;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 팔로우·구독과 팔로워·팔로잉 목록 (T101, T102, SOC-01, SOC-02). 누르면 하고 다시 누르면 취소. */
@RestController
public class FollowController {

  private final FollowService followService;

  public FollowController(FollowService followService) {
    this.followService = followService;
  }

  @PostMapping("/api/users/{id}/follow")
  public FollowResult follow(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
    return followService.toggleFollow(user.id(), id);
  }

  /** 일부 공개 블로그는 공유 링크의 share 값이 있어야 구독할 수 있다 (멤버는 없어도 된다). */
  @PostMapping("/api/blogs/{blogId}/subscribe")
  public SubscribeResult subscribe(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @RequestParam(required = false) String share) {
    return followService.toggleSubscribe(user.id(), blogId, share);
  }

  @GetMapping("/api/users/{id}/followers")
  public PageResponse<UserRow> followers(
      @PathVariable Long id, @RequestParam(defaultValue = "1") int page) {
    return followService.followers(id, page);
  }

  @GetMapping("/api/users/{id}/followings")
  public PageResponse<UserRow> followings(
      @PathVariable Long id, @RequestParam(defaultValue = "1") int page) {
    return followService.followings(id, page);
  }
}
