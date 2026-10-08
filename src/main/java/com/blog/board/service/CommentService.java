package com.blog.board.service;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.ManagerPermission;
import com.blog.blog.service.BlogAccessService;
import com.blog.board.domain.Comment;
import com.blog.board.domain.Post;
import com.blog.board.repository.CommentRepository;
import com.blog.board.repository.PostRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.AccountGuard;
import com.blog.common.security.AccountGuard.Activity;
import com.blog.common.security.BlogAuthz;
import com.blog.common.web.PageResponse;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 댓글·대댓글 (T067, BRD-06, D-78, D-87).
 *
 * <ul>
 *   <li>쓰기: 글을 볼 수 있는 회원 누구나(멤버가 아니어도 된다). 계정 정지·관리자 계정은 못 쓴다 (AccountGuard, SEC-09).
 *   <li>대댓글은 1단계. 답글에 다시 답하면 같은 첫 댓글 아래에 붙고, 답한 회원을 {@code reply_to_user_id}에 둔다.
 *   <li>수정: 쓴 사람만, "수정됨"이 붙는다. 삭제: 쓴 사람, 그 블로그 블로그장·글 관리 권한 부블로그장, 메인 관리자.
 *   <li>답글이 남은 첫 댓글을 지우면 deleted=true로 내려가 화면이 "삭제된 댓글입니다"로 보여 주고, 답글이 없으면 목록에서 빠진다.
 *   <li>알림: 글 작성자에게 "댓글", 답한 회원에게 "대댓글". 같은 사람이면 "대댓글" 하나만.
 * </ul>
 */
@Service
public class CommentService {

  public static final int PAGE_SIZE = 50;

  public record CommentForm(String content, Long parentId) {}

  /**
   * 댓글 한 개.
   *
   * @param content 지운 댓글이면 null
   * @param replyToNickname 대댓글이 답하는 회원 (@닉네임)
   */
  public record CommentRow(
      Long id,
      Long parentId,
      String authorNickname,
      String replyToNickname,
      String content,
      boolean deleted,
      boolean edited,
      LocalDateTime createdAt,
      boolean canEdit,
      boolean canDelete,
      List<CommentRow> replies) {}

  private final CommentRepository commentRepository;
  private final PostRepository postRepository;
  private final UserRepository userRepository;
  private final BlogAccessService accessService;
  private final BlogAuthz blogAuthz;
  private final AccountGuard accountGuard;
  private final NotificationService notificationService;
  private final Clock clock;

  public CommentService(
      CommentRepository commentRepository,
      PostRepository postRepository,
      UserRepository userRepository,
      BlogAccessService accessService,
      BlogAuthz blogAuthz,
      AccountGuard accountGuard,
      NotificationService notificationService,
      Clock clock) {
    this.commentRepository = commentRepository;
    this.postRepository = postRepository;
    this.userRepository = userRepository;
    this.accessService = accessService;
    this.blogAuthz = blogAuthz;
    this.accountGuard = accountGuard;
    this.notificationService = notificationService;
    this.clock = clock;
  }

  /** 댓글 또는 대댓글을 단다. */
  @Transactional
  public Comment create(Long postId, Long userId, String shareKey, CommentForm form) {
    accountGuard.check(userId, Activity.WRITE_COMMENT);
    Post post = visiblePost(postId, userId, shareKey);
    String content = validate(form.content());
    User author = userRepository.getReferenceById(userId);

    Comment parent = null;
    User replyTo = null;
    if (form.parentId() != null) {
      Comment target =
          commentRepository
              .findById(form.parentId())
              .filter(c -> c.getPost().getId().equals(postId))
              .filter(Comment::isActive)
              .orElseThrow(
                  () -> new BusinessException(ErrorCode.INVALID_INPUT, "답할 댓글이 지워졌거나 없어요."));
      parent = target.getParent() == null ? target : target.getParent();
      replyTo = target.getAuthor();
    }
    Comment comment = commentRepository.save(new Comment(post, author, parent, replyTo, content));
    postRepository.addCommentCount(postId, 1);
    notifyNew(post, comment, userId);
    return comment;
  }

  /** 쓴 사람만 고친다. */
  @Transactional
  public Comment update(Long commentId, Long userId, CommentForm form) {
    accountGuard.check(userId, Activity.WRITE_COMMENT);
    Comment comment = liveComment(commentId);
    if (!comment.isWrittenBy(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "댓글은 쓴 사람만 고칠 수 있어요.");
    }
    visiblePost(comment.getPost().getId(), userId, null);
    comment.edit(validate(form.content()));
    return comment;
  }

  /** 지운다. 행은 남기고 DELETED로 바꾼다. */
  @Transactional
  public void delete(Long commentId, Long userId, UserRole role) {
    Comment comment = liveComment(commentId);
    Long blogId = comment.getPost().getBlog().getId();
    if (!comment.isWrittenBy(userId) && !canModerate(blogId, role)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "이 댓글을 지울 권한이 없어요.");
    }
    comment.delete(LocalDateTime.now(clock));
    postRepository.addCommentCount(comment.getPost().getId(), -1);
  }

  /** 글의 댓글 목록. 첫 댓글 50개씩, 답글은 첫 댓글 아래에 모두. */
  @Transactional(readOnly = true)
  public PageResponse<CommentRow> list(
      Long postId, Long viewerId, UserRole viewerRole, String shareKey, int page) {
    Post post = visiblePost(postId, viewerId, shareKey);
    boolean moderator = viewerId != null && canModerate(post.getBlog().getId(), viewerRole);
    Page<Comment> roots =
        commentRepository.findRoots(postId, PageRequest.of(Math.max(page, 1) - 1, PAGE_SIZE));
    return PageResponse.of(roots, list -> rows(list, viewerId, moderator));
  }

  private List<CommentRow> rows(List<Comment> roots, Long viewerId, boolean moderator) {
    if (roots.isEmpty()) {
      return List.of();
    }
    Map<Long, List<CommentRow>> replies = new LinkedHashMap<>();
    roots.forEach(r -> replies.put(r.getId(), new ArrayList<>()));
    for (Comment r : commentRepository.findReplies(replies.keySet())) {
      replies.get(r.getParent().getId()).add(row(r, viewerId, moderator, List.of()));
    }
    return roots.stream().map(r -> row(r, viewerId, moderator, replies.get(r.getId()))).toList();
  }

  private CommentRow row(Comment c, Long viewerId, boolean moderator, List<CommentRow> replies) {
    boolean deleted = !c.isActive();
    boolean mine = viewerId != null && c.isWrittenBy(viewerId);
    return new CommentRow(
        c.getId(),
        c.getParent() == null ? null : c.getParent().getId(),
        deleted ? null : nickname(c.getAuthor()),
        c.getReplyToUser() == null ? null : nickname(c.getReplyToUser()),
        deleted ? null : c.getContent(),
        deleted,
        !deleted && c.isEdited(),
        c.getCreatedAt(),
        !deleted && mine,
        !deleted && (mine || moderator),
        replies);
  }

  private void notifyNew(Post post, Comment comment, Long actorId) {
    String actor = nickname(userRepository.getReferenceById(actorId));
    Long postAuthorId = post.getAuthor().getId();
    Long replyToId = comment.getReplyToUser() == null ? null : comment.getReplyToUser().getId();
    if (replyToId != null) {
      notificationService.notify(
          replyToId,
          actorId,
          NotificationType.REPLY,
          NotificationTargetType.POST,
          post.getId(),
          actor + "님이 내 댓글에 답글을 남겼어요.");
    }
    if (!Objects.equals(postAuthorId, replyToId)) {
      notificationService.notify(
          postAuthorId,
          actorId,
          NotificationType.COMMENT,
          NotificationTargetType.POST,
          post.getId(),
          actor + "님이 내 글 '" + post.getTitle() + "'에 댓글을 남겼어요.");
    }
  }

  /** 블로그장·글 관리 권한 부블로그장·메인 관리자 */
  private boolean canModerate(Long blogId, UserRole role) {
    return role == UserRole.ADMIN
        || blogAuthz.hasPermission(blogId, ManagerPermission.MANAGE_POSTS);
  }

  private Post visiblePost(Long postId, Long viewerId, String shareKey) {
    Post post =
        postRepository
            .findDetail(postId)
            .filter(Post::isPublished)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    accessService.check(post.getBlog(), viewerId, shareKey);
    return post;
  }

  private Comment liveComment(Long commentId) {
    Comment comment =
        commentRepository
            .findById(commentId)
            .filter(Comment::isActive)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (!comment.getPost().isPublished() || !comment.getPost().getBlog().isOpen()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    return comment;
  }

  static String validate(String raw) {
    String content = raw == null ? "" : raw.strip();
    int length = content.codePointCount(0, content.length());
    if (length < 1 || length > Comment.CONTENT_MAX) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "댓글은 1~500자로 입력해 주세요.");
    }
    return content;
  }

  /** 탈퇴한 회원은 "탈퇴한 계정"으로 보인다. */
  static String nickname(User user) {
    if (!user.isActive() || user.getNickname() == null) {
      return PostQueryService.HIDDEN_AUTHOR;
    }
    return user.getNickname();
  }
}
