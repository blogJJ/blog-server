package com.blog.social.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.repository.BlogSubscriptionRepository;
import com.blog.blog.service.BlogAccessService;
import com.blog.board.service.ImageService;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.AccountGuard;
import com.blog.common.security.AccountGuard.Activity;
import com.blog.common.web.PageResponse;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.domain.UserFollow;
import com.blog.social.repository.UserBlockRepository;
import com.blog.social.repository.UserFollowRepository;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팔로우·구독 (T101, T102, SOC-01, SOC-02, D-02). 누르면 하고, 다시 누르면 취소한다. 계정 정지 중이면 못 하고, 메인 관리자는 팔로우만
 * 못 한다 (AccountGuard). 새로 할 때만 상대(블로그는 블로그장)에게 알림이 간다.
 */
@Service
public class FollowService {

  public static final int PAGE_SIZE = 20;

  public record FollowResult(boolean following, long followerCount) {}

  public record SubscribeResult(boolean subscribed, int subscriberCount) {}

  /** 팔로워·팔로잉 목록 한 줄. 이메일·이름·전화번호는 주지 않는다. */
  public record UserRow(Long id, String nickname, String profileImage, String bio) {}

  private final UserFollowRepository followRepository;
  private final UserBlockRepository blockRepository;
  private final UserRepository userRepository;
  private final BlogRepository blogRepository;
  private final BlogSubscriptionRepository subscriptionRepository;
  private final BlogAccessService accessService;
  private final AccountGuard accountGuard;
  private final NotificationService notificationService;

  public FollowService(
      UserFollowRepository followRepository,
      UserBlockRepository blockRepository,
      UserRepository userRepository,
      BlogRepository blogRepository,
      BlogSubscriptionRepository subscriptionRepository,
      BlogAccessService accessService,
      AccountGuard accountGuard,
      NotificationService notificationService) {
    this.followRepository = followRepository;
    this.blockRepository = blockRepository;
    this.userRepository = userRepository;
    this.blogRepository = blogRepository;
    this.subscriptionRepository = subscriptionRepository;
    this.accessService = accessService;
    this.accountGuard = accountGuard;
    this.notificationService = notificationService;
  }

  /** 팔로우 (T101). 차단한 사이면 어느 쪽도 팔로우할 수 없다 (SOC-05). 취소는 언제든 된다. */
  @Transactional
  public FollowResult toggleFollow(Long me, Long targetId) {
    if (me.equals(targetId)) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "나 자신은 팔로우할 수 없어요.");
    }
    if (followRepository.deletePair(me, targetId) > 0) {
      return new FollowResult(false, followRepository.countFollowers(targetId));
    }
    accountGuard.check(me, Activity.FOLLOW);
    User target = activeUser(targetId);
    if (blockRepository.existsBetween(me, targetId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "차단한 사이에서는 팔로우할 수 없어요.");
    }
    if (followRepository.insertIgnore(me, target.getId()) > 0) {
      User actor = userRepository.getReferenceById(me);
      notificationService.notify(
          target.getId(),
          me,
          NotificationType.FOLLOW,
          NotificationTargetType.USER,
          me,
          actor.getNickname() + "님이 나를 팔로우해요.");
    }
    return new FollowResult(true, followRepository.countFollowers(targetId));
  }

  /**
   * 블로그 구독 (T101). 블로그를 볼 수 있어야 구독할 수 있다: 일부 공개는 공유 링크로 들어온 회원과 멤버, 비공개는 멤버만 (D-50). 취소는
   * 블로그가 비공개로 바뀐 뒤에도 된다.
   */
  @Transactional
  public SubscribeResult toggleSubscribe(Long me, Long blogId, String shareKey) {
    Blog blog =
        blogRepository.findById(blogId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (subscriptionRepository.deletePair(blogId, me) > 0) {
      blogRepository.addSubscriberCount(blogId, -1);
      return new SubscribeResult(false, Math.max(blog.getSubscriberCount() - 1, 0));
    }
    accountGuard.check(me, Activity.SUBSCRIBE);
    accessService.check(blog, me, shareKey);
    int added = subscriptionRepository.insertIgnore(blogId, me);
    if (added > 0) {
      blogRepository.addSubscriberCount(blogId, 1);
      User actor = userRepository.getReferenceById(me);
      notificationService.notify(
          blog.getOwner().getId(),
          me,
          NotificationType.BLOG_SUBSCRIBE,
          NotificationTargetType.USER,
          me,
          actor.getNickname() + "님이 '" + blog.getName() + "' 블로그를 구독해요.");
    }
    return new SubscribeResult(true, blog.getSubscriberCount() + added);
  }

  /** 이 회원을 팔로우하는 사람들 (T102) */
  @Transactional(readOnly = true)
  public PageResponse<UserRow> followers(Long userId, int page) {
    activeUser(userId);
    return PageResponse.of(
        followRepository.findFollowers(userId, pageOf(page)), rows(UserFollow::getFollower));
  }

  /** 이 회원이 팔로우하는 사람들 (T102) */
  @Transactional(readOnly = true)
  public PageResponse<UserRow> followings(Long userId, int page) {
    activeUser(userId);
    return PageResponse.of(
        followRepository.findFollowings(userId, pageOf(page)), rows(UserFollow::getFollowee));
  }

  @Transactional(readOnly = true)
  public boolean isFollowing(Long me, Long targetId) {
    return me != null && followRepository.existsByFollowerIdAndFolloweeId(me, targetId);
  }

  private static Function<List<UserFollow>, List<UserRow>> rows(Function<UserFollow, User> side) {
    return list ->
        list.stream()
            .map(side)
            .map(
                u ->
                    new UserRow(
                        u.getId(), u.getNickname(), ImageService.url(u.getProfileImage()), u.getBio()))
            .toList();
  }

  private User activeUser(Long userId) {
    return userRepository
        .findById(userId)
        .filter(User::isActive)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static PageRequest pageOf(int page) {
    return PageRequest.of(Math.max(page, 1) - 1, PAGE_SIZE);
  }
}
