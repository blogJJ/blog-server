package com.blog.board.service;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.ManagerPermission;
import com.blog.blog.repository.BlogManagerPermissionRepository;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.board.domain.Comment;
import com.blog.board.repository.CommentRepository;
import com.blog.board.repository.PostRepository;
import com.blog.board.service.ClaudeCommentFilterClient.Result;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.ratelimit.RateLimiter;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 새 댓글 자동 검사와 블로그장 확인 (T166, D-133).
 *
 * <ul>
 *   <li>댓글을 쓰거나 고친 저장이 끝난 뒤 따로(댓글 저장은 기다리지 않음) Claude에게 스팸·광고·욕설·개인정보 노출인지 묻는다. 블로그장·부블로그장·메인 관리자가
 *       쓴 댓글은 묻지 않는다.
 *   <li>수상하면 숨기고(status HIDDEN, review_status PENDING) 블로그장과 글 관리 권한 부블로그장에게 알린다. 쓴 사람에게는 그대로 보여
 *       숨겨진 줄 모른다. 읽는 사람 화면에는 검사 이야기가 나오지 않는다.
 *   <li>블로그 관리 화면의 "댓글 확인"에서 승인하면 다시 보이고(APPROVED), 삭제하면 지운다. 메인 관리자도 할 수 있다(D-122).
 *   <li>키가 없거나 실패·시간 초과면 그대로 둔다(fail-open). 회원별·블로그별·전체 횟수를 넘으면 묻지 않고 그대로 둔다.
 *   <li>승인한 댓글을 고치면 다시 검사하고, 고친 내용이 수상할 때만 다시 숨긴다. 확인을 기다리는 댓글을 고치면 그대로 기다린다.
 * </ul>
 */
@Service
public class CommentModerationService {

  /**
   * 댓글이 저장되었다(새로 쓰거나 고침). 커밋 뒤에 검사한다.
   *
   * @param authorId 쓴 회원. 횟수 제한에 쓴다
   */
  public record CommentWritten(Long commentId, Long blogId, Long authorId) {}

  /** 확인 목록 한 줄 */
  public record HeldRow(
      Long id,
      Long postId,
      String postTitle,
      Long parentId,
      String authorNickname,
      String content,
      String reason,
      boolean edited,
      LocalDateTime createdAt,
      LocalDateTime heldAt) {}

  private static final Logger log = LoggerFactory.getLogger(CommentModerationService.class);
  private static final Duration WINDOW = Duration.ofHours(1);

  private final CommentRepository commentRepository;
  private final PostRepository postRepository;
  private final BlogMemberRepository memberRepository;
  private final BlogManagerPermissionRepository permissionRepository;
  private final NotificationService notificationService;
  private final ClaudeCommentFilterClient claude;
  private final RateLimiter rateLimiter;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final boolean enabled;
  private final int perUser;
  private final int perBlog;
  private final int total;
  // Claude 호출이 몰리지 않게 둘까지만 같이 묻고, 밀리면 100개까지 기다리게 한다. 그보다 많으면 검사하지 않고 그대로 둔다
  private final ThreadPoolExecutor worker;

  public CommentModerationService(
      CommentRepository commentRepository,
      PostRepository postRepository,
      BlogMemberRepository memberRepository,
      BlogManagerPermissionRepository permissionRepository,
      NotificationService notificationService,
      ClaudeCommentFilterClient claude,
      RateLimiter rateLimiter,
      PlatformTransactionManager transactionManager,
      Clock clock,
      @Value("${app.comment-filter.enabled:true}") boolean enabled,
      @Value("${app.comment-filter.per-user-per-hour:20}") int perUser,
      @Value("${app.comment-filter.per-blog-per-hour:200}") int perBlog,
      @Value("${app.comment-filter.per-hour:1000}") int total) {
    this.commentRepository = commentRepository;
    this.postRepository = postRepository;
    this.memberRepository = memberRepository;
    this.permissionRepository = permissionRepository;
    this.notificationService = notificationService;
    this.claude = claude;
    this.rateLimiter = rateLimiter;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
    this.enabled = enabled;
    this.perUser = perUser;
    this.perBlog = perBlog;
    this.total = total;
    AtomicInteger seq = new AtomicInteger();
    this.worker =
        new ThreadPoolExecutor(
            2,
            2,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            r -> {
              Thread t = new Thread(r, "comment-filter-" + seq.incrementAndGet());
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    this.worker.allowCoreThreadTimeOut(true);
  }

  @TransactionalEventListener
  public void onCommentWritten(CommentWritten event) {
    if (!enabled || !claude.enabled()) {
      return;
    }
    try {
      worker.execute(() -> reviewSafely(event));
    } catch (RejectedExecutionException e) {
      log.warn("댓글 검사 대기열이 가득 차 댓글 {}을(를) 검사하지 않음", event.commentId());
    }
  }

  private void reviewSafely(CommentWritten event) {
    try {
      review(event);
    } catch (RuntimeException e) {
      log.warn("댓글 {} 검사 중 오류, 그대로 둠: {}", event.commentId(), e.toString());
    }
  }

  /**
   * 댓글 하나를 검사하고 수상하면 숨긴다. 보통은 커밋 뒤 작업 스레드에서 불린다.
   *
   * @return 숨겼으면 true
   */
  public boolean review(CommentWritten event) {
    String content =
        tx.execute(
            s ->
                commentRepository
                    .findById(event.commentId())
                    .filter(Comment::isActive)
                    .map(Comment::getContent)
                    .orElse(null));
    if (content == null) {
      return false;
    }
    if (!withinLimits(event)) {
      log.info("댓글 검사 횟수를 넘어 댓글 {}을(를) 검사하지 않음", event.commentId());
      return false;
    }
    Optional<Result> result = claude.check(content);
    if (result.isEmpty()) {
      return false; // 실패는 클라이언트가 남긴다. 댓글은 그대로
    }
    if (!result.get().suspect()) {
      return false;
    }
    Boolean held = tx.execute(s -> hold(event.commentId(), content, result.get()));
    return Boolean.TRUE.equals(held);
  }

  private boolean withinLimits(CommentWritten event) {
    return rateLimiter
            .tryAcquire("comment-filter:user:" + event.authorId(), perUser, WINDOW)
            .allowed()
        && rateLimiter
            .tryAcquire("comment-filter:blog:" + event.blogId(), perBlog, WINDOW)
            .allowed()
        && rateLimiter.tryAcquire("comment-filter:all", total, WINDOW).allowed();
  }

  /** 검사하는 사이에 지워지거나 고쳐졌으면 숨기지 않는다(고친 내용은 따로 검사한다). */
  private boolean hold(Long commentId, String checkedContent, Result result) {
    Comment comment = commentRepository.findById(commentId).orElse(null);
    if (comment == null || !comment.isActive() || !checkedContent.equals(comment.getContent())) {
      return false;
    }
    comment.hold(result.reason(), LocalDateTime.now(clock));
    postRepository.addCommentCount(comment.getPost().getId(), -1);
    Blog blog = comment.getPost().getBlog();
    List<Long> receivers = new ArrayList<>();
    receivers.add(blog.getOwner().getId());
    for (BlogMember manager : memberRepository.findManagers(blog.getId())) {
      if (permissionRepository.existsByBlogMemberIdAndPermission(
          manager.getId(), ManagerPermission.MANAGE_POSTS)) {
        receivers.add(manager.getUser().getId());
      }
    }
    receivers.remove(comment.getAuthor().getId());
    notificationService.notifyAll(
        receivers,
        null,
        NotificationType.COMMENT_REVIEW,
        NotificationTargetType.BLOG,
        blog.getId(),
        "'" + blog.getName() + "'에 확인이 필요한 댓글이 있어요.");
    log.info("댓글 {}을(를) 숨기고 블로그장 확인을 기다림: {}", commentId, result.reason());
    return true;
  }

  /** 블로그의 확인을 기다리는 댓글. 권한은 컨트롤러가 본다 */
  @Transactional(readOnly = true)
  public List<HeldRow> held(Long blogId) {
    return commentRepository.findHeld(blogId).stream()
        .map(
            c ->
                new HeldRow(
                    c.getId(),
                    c.getPost().getId(),
                    c.getPost().getTitle(),
                    c.getParent() == null ? null : c.getParent().getId(),
                    CommentService.nickname(c.getAuthor()),
                    c.getContent(),
                    c.getReviewReason() == null ? null : c.getReviewReason().name(),
                    c.isEdited(),
                    c.getCreatedAt(),
                    c.getHeldAt()))
        .toList();
  }

  /** 승인: 다시 보이게 한다. 이후 고치지 않으면 다시 검사하지 않는다. */
  @Transactional
  public void approve(Long blogId, Long commentId) {
    Comment comment = heldIn(blogId, commentId);
    comment.approve();
    postRepository.addCommentCount(comment.getPost().getId(), 1);
  }

  /** 삭제: 일반 삭제처럼 행은 남기고 DELETED로 바꾼다. 숨길 때 댓글 수에서 이미 뺐다. */
  @Transactional
  public void delete(Long blogId, Long commentId) {
    heldIn(blogId, commentId).delete(LocalDateTime.now(clock));
  }

  private Comment heldIn(Long blogId, Long commentId) {
    return commentRepository
        .findById(commentId)
        .filter(c -> c.getPost().getBlog().getId().equals(blogId))
        .filter(Comment::isHeld)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이미 처리했거나 없는 댓글이에요."));
  }

  /** 쓴 사람이 블로그장·부블로그장(멤버 역할)이면 검사하지 않는다 */
  static boolean isStaff(Optional<BlogMember> membership) {
    return membership.filter(m -> m.isOwner() || m.isManager()).isPresent();
  }

  @PreDestroy
  void close() {
    worker.shutdownNow();
  }
}
