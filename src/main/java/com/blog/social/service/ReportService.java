package com.blog.social.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.service.BlogAccessService;
import com.blog.blog.service.MemberSanctionService;
import com.blog.board.domain.Comment;
import com.blog.board.domain.Post;
import com.blog.board.repository.CommentRepository;
import com.blog.board.repository.PostRepository;
import com.blog.board.service.ContentRenderer;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.social.domain.HandlerScope;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.domain.Report;
import com.blog.social.domain.ReportReason;
import com.blog.social.domain.ReportStatus;
import com.blog.social.domain.ReportTargetType;
import com.blog.social.repository.ReportRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고 접수와 블로그 안 신고 처리 (T075, T080, SOC-06, BLG-13, D-45, D-85, D-94, D-95, D-100, D-114).
 *
 * <p>누가 처리하나({@code handler_scope}):
 *
 * <ul>
 *   <li>블로그 안의 회원·글·댓글: 블로그장(과 멤버 관리 권한 부블로그장)
 *   <li>블로그 자체, 블로그장 본인, 블로그장이 쓴 글·댓글, 메인 프로필 회원(블로그 없이), 블로그장이 계정 정지 중이고 부블로그장이 없는 블로그: 메인 관리자
 * </ul>
 *
 * <p>같은 사람은 같은 대상을 2주에 한 번만 신고한다. 신고 당시 대상 내용을 {@code target_snapshot}에 남긴다.
 */
@Service
public class ReportService {

  public static final Duration REPEAT_AFTER = Duration.ofDays(14);
  private static final int EXCERPT = 200;

  /**
   * 화면에서 받은 신고.
   *
   * @param blogId 회원 신고를 블로그 안에서 했으면 그 블로그. 메인 프로필 신고는 null
   */
  public record ReportForm(
      ReportTargetType targetType,
      Long targetId,
      Long blogId,
      ReportReason reason,
      String detail) {}

  /** 블로그 안 신고 처리 결과 */
  public enum Resolution {
    NO_ISSUE,
    WARN,
    SUSPEND,
    KICK
  }

  private final ReportRepository reportRepository;
  private final PostRepository postRepository;
  private final CommentRepository commentRepository;
  private final BlogRepository blogRepository;
  private final BlogMemberRepository memberRepository;
  private final UserRepository userRepository;
  private final BlogAccessService accessService;
  private final MemberSanctionService sanctionService;
  private final NotificationService notificationService;
  private final ContentRenderer renderer;
  private final Clock clock;

  public ReportService(
      ReportRepository reportRepository,
      PostRepository postRepository,
      CommentRepository commentRepository,
      BlogRepository blogRepository,
      BlogMemberRepository memberRepository,
      UserRepository userRepository,
      BlogAccessService accessService,
      MemberSanctionService sanctionService,
      NotificationService notificationService,
      ContentRenderer renderer,
      Clock clock) {
    this.reportRepository = reportRepository;
    this.postRepository = postRepository;
    this.commentRepository = commentRepository;
    this.blogRepository = blogRepository;
    this.memberRepository = memberRepository;
    this.userRepository = userRepository;
    this.accessService = accessService;
    this.sanctionService = sanctionService;
    this.notificationService = notificationService;
    this.renderer = renderer;
    this.clock = clock;
  }

  /** 신고 접수 (T075) */
  @Transactional
  public Report create(Long reporterId, ReportForm form) {
    if (form.targetType() == null || form.targetId() == null || form.reason() == null) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "신고 대상과 사유를 골라 주세요.");
    }
    String detail = form.detail() == null ? null : form.detail().strip();
    if (detail != null && detail.codePointCount(0, detail.length()) > Report.DETAIL_MAX) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "자세한 내용은 500자까지 쓸 수 있어요.");
    }
    Target target = resolve(form, reporterId);
    if (target.authorId() != null && target.authorId().equals(reporterId)) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "자기 자신이나 자기 글은 신고할 수 없어요.");
    }
    LocalDateTime now = LocalDateTime.now(clock);
    if (reportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndCreatedAtAfter(
        reporterId, form.targetType(), form.targetId(), now.minus(REPEAT_AFTER))) {
      throw new BusinessException(ErrorCode.CONFLICT, "같은 대상은 2주에 한 번만 신고할 수 있어요.");
    }
    return reportRepository.save(
        new Report(
            userRepository.getReferenceById(reporterId),
            form.targetType(),
            form.targetId(),
            target.blog(),
            scope(target, now),
            form.reason(),
            detail == null || detail.isEmpty() ? null : detail,
            cut(target.snapshot())));
  }

  /** 블로그장이 보는 대기 중인 신고 (T080). 권한은 컨트롤러가 본다 */
  @Transactional(readOnly = true)
  public List<Report> pendingForBlog(Long blogId) {
    return reportRepository.findForBlog(blogId, HandlerScope.BLOG_OWNER, ReportStatus.PENDING);
  }

  /**
   * 블로그 안 신고 처리 (T080). 문제 없음·경고·정지·강제 퇴장 중 하나. 정지·강제 퇴장은 대상이 그 블로그 멤버일 때만. 처리 결과는 신고자에게 알린다.
   *
   * @param days 정지 기간 3·14·30, 영구는 null
   */
  @Transactional
  public Report resolveInBlog(
      Long blogId,
      Long reportId,
      Long actorId,
      Resolution resolution,
      Integer days,
      String reason) {
    Report report =
        reportRepository
            .findById(reportId)
            .filter(r -> r.getBlog() != null && r.getBlog().getId().equals(blogId))
            .filter(r -> r.getHandlerScope() == HandlerScope.BLOG_OWNER)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (!report.isPending()) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 처리된 신고예요.");
    }
    if (resolution == null) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "처리 결과를 골라 주세요.");
    }
    Long targetUserId = targetUserId(report);
    if (targetUserId != null && targetUserId.equals(actorId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "나에 대한 신고는 다른 관리자가 처리해야 해요.");
    }
    ReportStatus status =
        switch (resolution) {
          case NO_ISSUE -> ReportStatus.NO_ISSUE;
          case WARN -> {
            sanctionService.warn(blogId, requireUser(targetUserId), actorId, reason, reportId);
            yield ReportStatus.WARNED;
          }
          case SUSPEND -> {
            sanctionService.suspend(
                blogId, requireUser(targetUserId), actorId, days, reason, reportId);
            yield ReportStatus.SUSPENDED;
          }
          case KICK -> {
            sanctionService.kick(blogId, requireUser(targetUserId), actorId, reason, reportId);
            yield ReportStatus.KICKED;
          }
        };
    report.resolve(status, userRepository.getReferenceById(actorId), LocalDateTime.now(clock));
    notificationService.notify(
        report.getReporter().getId(),
        actorId,
        NotificationType.REPORT_RESULT,
        NotificationTargetType.REPORT,
        report.getId(),
        "신고하신 내용이 처리되었어요: " + label(status));
    return report;
  }

  /** 신고 대상이 가리키는 회원(글·댓글은 작성자). 블로그 신고는 null */
  public Long targetUserId(Report report) {
    return switch (report.getTargetType()) {
      case USER -> report.getTargetId();
      case POST ->
          postRepository
              .findById(report.getTargetId())
              .map(p -> p.getAuthor().getId())
              .orElse(null);
      case COMMENT ->
          commentRepository
              .findById(report.getTargetId())
              .map(c -> c.getAuthor().getId())
              .orElse(null);
      case BLOG -> null;
    };
  }

  /** 대상을 찾고, 신고자가 볼 수 있는지 확인하고, 블로그·작성자·내용을 모은다 */
  private Target resolve(ReportForm form, Long reporterId) {
    return switch (form.targetType()) {
      case POST -> {
        Post post =
            postRepository
                .findDetail(form.targetId())
                .filter(Post::isPublished)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        accessService.check(post.getBlog(), reporterId, null);
        yield new Target(
            post.getBlog(),
            post.getAuthor().getId(),
            "[글] " + post.getTitle() + "\n" + renderer.excerpt(post.getContent(), EXCERPT));
      }
      case COMMENT -> {
        Comment comment =
            commentRepository
                .findById(form.targetId())
                .filter(Comment::isActive)
                .filter(c -> c.getPost().isPublished())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Blog blog = comment.getPost().getBlog();
        accessService.check(blog, reporterId, null);
        yield new Target(
            blog,
            comment.getAuthor().getId(),
            "[댓글] "
                + java.util.Objects.toString(comment.getAuthor().getNickname(), "탈퇴한 계정")
                + ": "
                + comment.getContent());
      }
      case BLOG -> {
        Blog blog =
            blogRepository
                .findById(form.targetId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        accessService.check(blog, reporterId, null);
        yield new Target(
            blog,
            blog.getOwner().getId(),
            "[블로그] "
                + blog.getName()
                + (blog.getDescription() == null ? "" : "\n" + blog.getDescription()));
      }
      case USER -> {
        User user =
            userRepository
                .findById(form.targetId())
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Blog blog = null;
        if (form.blogId() != null) {
          blog =
              blogRepository
                  .findById(form.blogId())
                  .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
          accessService.check(blog, reporterId, null);
          if (!memberRepository.existsByBlogIdAndUserId(blog.getId(), user.getId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이 블로그의 멤버가 아니에요.");
          }
        }
        yield new Target(blog, user.getId(), "[회원] " + user.getNickname());
      }
    };
  }

  /** 처리할 사람 (D-45, D-94, D-95, D-100, D-114) */
  private HandlerScope scope(Target target, LocalDateTime now) {
    Blog blog = target.blog();
    if (blog == null) {
      return HandlerScope.ADMIN; // 메인 프로필 신고
    }
    User owner = blog.getOwner();
    boolean aboutOwner = owner.getId().equals(target.authorId());
    if (aboutOwner) {
      return HandlerScope.ADMIN; // 블로그 자체·블로그장 본인·블로그장이 쓴 글·댓글
    }
    if (owner.isSuspendedAt(now) && memberRepository.findManagers(blog.getId()).isEmpty()) {
      return HandlerScope.ADMIN;
    }
    return HandlerScope.BLOG_OWNER;
  }

  private static Long requireUser(Long userId) {
    if (userId == null) {
      throw new BusinessException(ErrorCode.CONFLICT, "대상 회원을 찾을 수 없어요. 문제 없음으로 처리해 주세요.");
    }
    return userId;
  }

  private static String label(ReportStatus status) {
    return switch (status) {
      case NO_ISSUE -> "문제 없음";
      case WARNED -> "경고";
      case SUSPENDED -> "정지";
      case KICKED -> "강제 퇴장";
      default -> status.name();
    };
  }

  private static String cut(String snapshot) {
    return snapshot.length() <= Report.SNAPSHOT_MAX
        ? snapshot
        : snapshot.substring(0, Report.SNAPSHOT_MAX);
  }

  private record Target(Blog blog, Long authorId, String snapshot) {}
}
