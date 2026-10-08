package com.blog.board.service;

import com.blog.auth.repository.UserRepository;
import com.blog.blog.service.BlogAccessService;
import com.blog.board.domain.Post;
import com.blog.board.repository.PostLikeRepository;
import com.blog.board.repository.PostRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.AccountGuard;
import com.blog.common.security.AccountGuard.Activity;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좋아요 (T068, BRD-06, SOC-01). 누르면 좋아요, 다시 누르면 취소. 글을 볼 수 있는 회원 누구나. 계정 정지·관리자 계정은 못 누른다. 좋아요를 누를 때만
 * 글 작성자에게 알림이 간다.
 */
@Service
public class LikeService {

  public record LikeResult(boolean liked, int likeCount) {}

  private final PostRepository postRepository;
  private final PostLikeRepository likeRepository;
  private final UserRepository userRepository;
  private final BlogAccessService accessService;
  private final AccountGuard accountGuard;
  private final NotificationService notificationService;

  public LikeService(
      PostRepository postRepository,
      PostLikeRepository likeRepository,
      UserRepository userRepository,
      BlogAccessService accessService,
      AccountGuard accountGuard,
      NotificationService notificationService) {
    this.postRepository = postRepository;
    this.likeRepository = likeRepository;
    this.userRepository = userRepository;
    this.accessService = accessService;
    this.accountGuard = accountGuard;
    this.notificationService = notificationService;
  }

  @Transactional
  public LikeResult toggle(Long postId, Long userId, String shareKey) {
    accountGuard.check(userId, Activity.LIKE);
    Post post =
        postRepository
            .findDetail(postId)
            .filter(Post::isPublished)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    accessService.check(post.getBlog(), userId, shareKey);

    boolean liked;
    int delta;
    if (likeRepository.deleteByPostIdAndUserId(postId, userId) > 0) {
      liked = false;
      delta = -1;
    } else {
      liked = true;
      delta = likeRepository.insertIgnore(postId, userId);
    }
    if (delta != 0) {
      postRepository.addLikeCount(postId, delta);
    }
    if (liked && delta > 0) {
      String actor = CommentService.nickname(userRepository.getReferenceById(userId));
      notificationService.notify(
          post.getAuthor().getId(),
          userId,
          NotificationType.POST_LIKE,
          NotificationTargetType.POST,
          postId,
          actor + "님이 내 글 '" + post.getTitle() + "'을(를) 좋아해요.");
    }
    return new LikeResult(liked, post.getLikeCount() + delta);
  }

  @Transactional(readOnly = true)
  public boolean isLiked(Long postId, Long userId) {
    return userId != null && likeRepository.existsByPostIdAndUserId(postId, userId);
  }
}
