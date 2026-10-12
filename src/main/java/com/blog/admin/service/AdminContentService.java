package com.blog.admin.service;

import com.blog.admin.domain.AdminAction;
import com.blog.admin.domain.AdminTargetType;
import com.blog.admin.repository.AdminQueryRepository;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.repository.BlogRepository;
import com.blog.board.domain.Comment;
import com.blog.board.domain.CommentStatus;
import com.blog.board.domain.Post;
import com.blog.board.domain.PostStatus;
import com.blog.board.repository.CommentRepository;
import com.blog.board.repository.PostRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.web.PageResponse;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 글·댓글 숨김·삭제와 글 복구 (T119, ADM-03, 4.5). 어느 블로그의 글이든 다룬다.
 *
 * <ul>
 *   <li>숨김: 글은 HIDDEN, 댓글은 HIDDEN. 일반 화면에서 빠지고(댓글은 "삭제된 댓글"처럼 보임) 글 수·댓글 수에서 뺀다.
 *   <li>삭제: 행은 남기고 DELETED(30일 보관). 작성자에게 "내 글 삭제됨" 알림(끌 수 없음)을 보낸다.
 *   <li>복구: 숨긴 글, 또는 삭제한 지 30일 안의 글을 다시 게시한다. 30일이 지나면 정리 배치가 지우므로 복구할 수 없다.
 *   <li>모든 조치를 관리자 활동 기록에 남긴다. 사유는 선택.
 * </ul>
 */
@Service
public class AdminContentService {

  public static final Duration RESTORE_WITHIN = Duration.ofDays(30);

  public record PostRow(
      Long id,
      Long blogId,
      String blogSlug,
      String blogName,
      String title,
      Long authorId,
      String authorNickname,
      String status,
      boolean notice,
      int commentCount,
      LocalDateTime deletedAt,
      LocalDateTime createdAt) {}

  public record CommentRow(
      Long id,
      Long parentId,
      Long authorId,
      String authorNickname,
      String content,
      String status,
      LocalDateTime createdAt) {}

  private final PostRepository postRepository;
  private final CommentRepository commentRepository;
  private final BlogRepository blogRepository;
  private final UserRepository userRepository;
  private final AdminQueryRepository queryRepository;
  private final NotificationService notificationService;
  private final AdminActionLogger actionLogger;
  private final Clock clock;

  public AdminContentService(
      PostRepository postRepository,
      CommentRepository commentRepository,
      BlogRepository blogRepository,
      UserRepository userRepository,
      AdminQueryRepository queryRepository,
      NotificationService notificationService,
      AdminActionLogger actionLogger,
      Clock clock) {
    this.postRepository = postRepository;
    this.commentRepository = commentRepository;
    this.blogRepository = blogRepository;
    this.userRepository = userRepository;
    this.queryRepository = queryRepository;
    this.notificationService = notificationService;
    this.actionLogger = actionLogger;
    this.clock = clock;
  }

  /** 제목으로 글 검색 (숨김·삭제 포함) */
  @Transactional(readOnly = true)
  public PageResponse<PostRow> searchPosts(String q, int page) {
    return PageResponse.of(
        queryRepository.searchPosts(AdminSearch.like(q), AdminSearch.page(page)),
        list -> list.stream().map(AdminContentService::row).toList());
  }

  /** 한 글의 댓글 모두 (숨김·삭제 포함) */
  @Transactional(readOnly = true)
  public List<CommentRow> comments(Long postId) {
    postRepository.findById(postId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    return queryRepository.commentsOf(postId).stream()
        .map(
            c ->
                new CommentRow(
                    c.getId(),
                    c.getParent() == null ? null : c.getParent().getId(),
                    c.getAuthor().getId(),
                    c.getAuthor().getNickname(),
                    c.getContent(),
                    c.getStatus().name(),
                    c.getCreatedAt()))
        .toList();
  }

  @Transactional
  public void hidePost(Long postId, Long adminId, String reason) {
    Post post = post(postId);
    if (!post.isPublished()) {
      throw new BusinessException(ErrorCode.CONFLICT, "게시 중인 글만 숨길 수 있어요.");
    }
    post.hide();
    blogRepository.addPostCount(post.getBlog().getId(), -1);
    actionLogger.log(adminId, AdminAction.HIDE_POST, AdminTargetType.POST, postId, reason);
  }

  @Transactional
  public void deletePost(Long postId, Long adminId, String reason) {
    Post post = post(postId);
    if (post.getStatus() == PostStatus.DELETED) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 삭제된 글이에요.");
    }
    if (post.isPublished()) {
      blogRepository.addPostCount(post.getBlog().getId(), -1);
    }
    post.delete(userRepository.getReferenceById(adminId), now());
    actionLogger.log(adminId, AdminAction.DELETE_POST, AdminTargetType.POST, postId, reason);
    notificationService.notify(
        post.getAuthor().getId(),
        adminId,
        NotificationType.POST_DELETED,
        NotificationTargetType.BLOG,
        post.getBlog().getId(),
        "관리자가 내 글 '" + post.getTitle() + "'을(를) 삭제했어요." + reasonSuffix(reason));
  }

  /** 숨긴 글이나 삭제한 지 30일 안의 글을 다시 게시한다 */
  @Transactional
  public void restorePost(Long postId, Long adminId, String reason) {
    Post post = post(postId);
    if (post.isPublished()) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 게시 중인 글이에요.");
    }
    if (!post.getBlog().isOpen()) {
      throw new BusinessException(ErrorCode.CONFLICT, "폐쇄된 블로그의 글은 복구할 수 없어요.");
    }
    if (post.getStatus() == PostStatus.DELETED
        && (post.getDeletedAt() == null
            || post.getDeletedAt().isBefore(now().minus(RESTORE_WITHIN)))) {
      throw new BusinessException(ErrorCode.CONFLICT, "삭제한 지 30일이 지난 글은 복구할 수 없어요.");
    }
    post.restore();
    blogRepository.addPostCount(post.getBlog().getId(), 1);
    actionLogger.log(adminId, AdminAction.RESTORE_POST, AdminTargetType.POST, postId, reason);
  }

  @Transactional
  public void hideComment(Long commentId, Long adminId, String reason) {
    Comment comment = comment(commentId);
    if (!comment.isActive()) {
      throw new BusinessException(ErrorCode.CONFLICT, "게시 중인 댓글만 숨길 수 있어요.");
    }
    comment.hide();
    postRepository.addCommentCount(comment.getPost().getId(), -1);
    actionLogger.log(adminId, AdminAction.HIDE_COMMENT, AdminTargetType.COMMENT, commentId, reason);
  }

  @Transactional
  public void deleteComment(Long commentId, Long adminId, String reason) {
    Comment comment = comment(commentId);
    if (comment.getStatus() == CommentStatus.DELETED) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 삭제된 댓글이에요.");
    }
    if (comment.isActive()) {
      postRepository.addCommentCount(comment.getPost().getId(), -1);
    }
    comment.delete(now());
    actionLogger.log(
        adminId, AdminAction.DELETE_COMMENT, AdminTargetType.COMMENT, commentId, reason);
    notificationService.notify(
        comment.getAuthor().getId(),
        adminId,
        NotificationType.POST_DELETED,
        NotificationTargetType.POST,
        comment.getPost().getId(),
        "관리자가 내 댓글 '" + excerpt(comment.getContent()) + "'을(를) 삭제했어요." + reasonSuffix(reason));
  }

  private Post post(Long postId) {
    return postRepository
        .findDetail(postId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private Comment comment(Long commentId) {
    return commentRepository
        .findById(commentId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static String reasonSuffix(String reason) {
    return reason == null || reason.isBlank() ? "" : " 사유: " + reason.strip();
  }

  private static String excerpt(String content) {
    String text = Objects.toString(content, "");
    return text.codePointCount(0, text.length()) <= 20
        ? text
        : text.substring(0, text.offsetByCodePoints(0, 20)) + "…";
  }

  private static PostRow row(Post p) {
    return new PostRow(
        p.getId(),
        p.getBlog().getId(),
        p.getBlog().getSlug(),
        p.getBlog().getName(),
        p.getTitle(),
        p.getAuthor().getId(),
        p.getAuthor().getNickname(),
        p.getStatus().name(),
        p.isNotice(),
        p.getCommentCount(),
        p.getDeletedAt(),
        p.getCreatedAt());
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
