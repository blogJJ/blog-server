package com.blog.admin.service;

import com.blog.admin.domain.AdminAction;
import com.blog.admin.domain.AdminTargetType;
import com.blog.admin.repository.AdminQueryRepository;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.CloseReason;
import com.blog.blog.service.BlogCloseService;
import com.blog.blog.service.MemberSanctionService;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.social.domain.HandlerScope;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.domain.Report;
import com.blog.social.domain.ReportStatus;
import com.blog.social.domain.ReportTargetType;
import com.blog.social.repository.ReportRepository;
import com.blog.social.service.NotificationService;
import com.blog.social.service.ReportService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메인 관리자의 신고 처리 (T123, ADM-04, ADM-08, D-94, D-95, D-99, D-100, D-114). {@code handler_scope =
 * ADMIN}인 신고만 다룬다. 신고 종류({@link Kind})마다 고를 수 있는 처리 결과가 다르다:
 *
 * <ul>
 *   <li>{@link Kind#PROFILE} 메인 프로필 회원 신고(블로그 없음): 문제 없음·경고·프로필 초기화·계정 정지 (D-95, D-99)
 *   <li>{@link Kind#OWNER} 블로그 자체, 블로그장 본인, 블로그장이 쓴 글·댓글: 문제 없음·블로그장 경고·블로그장 강퇴·블로그 폐쇄 (D-94)
 *   <li>{@link Kind#IN_BLOG} 블로그장이 계정 정지 중이고 부블로그장이 없을 때 들어온 블로그 안 신고: 문제 없음·경고·정지·강제 퇴장. 블로그장 대신
 *       MemberSanctionService로 처리한다 (D-100, D-114)
 * </ul>
 *
 * <p>종류는 처리할 때의 블로그장 기준으로 정한다(그사이 블로그장이 바뀌었으면 새 블로그장 기준). 처리 결과는 신고자에게 알리고 관리자 활동 기록에 남긴다.
 */
@Service
public class AdminReportService {

  public static final int PAGE_SIZE = 20;

  /** 신고 종류와 고를 수 있는 처리 결과 */
  public enum Kind {
    PROFILE(
        List.of(
            Resolution.NO_ISSUE,
            Resolution.WARN,
            Resolution.PROFILE_RESET,
            Resolution.ACCOUNT_SUSPEND)),
    OWNER(
        List.of(
            Resolution.NO_ISSUE, Resolution.WARN, Resolution.OWNER_DEMOTE, Resolution.BLOG_CLOSE)),
    IN_BLOG(List.of(Resolution.NO_ISSUE, Resolution.WARN, Resolution.SUSPEND, Resolution.KICK));

    private final List<Resolution> resolutions;

    Kind(List<Resolution> resolutions) {
      this.resolutions = resolutions;
    }

    public List<Resolution> resolutions() {
      return resolutions;
    }
  }

  /** 관리자 처리 결과 */
  public enum Resolution {
    NO_ISSUE,
    /** 종류에 따라 계정 경고(PROFILE), 블로그장 경고(OWNER), 멤버 경고(IN_BLOG) */
    WARN,
    /** 멤버 정지 (IN_BLOG) */
    SUSPEND,
    /** 멤버 강제 퇴장 (IN_BLOG) */
    KICK,
    /** 블로그장 강퇴 (OWNER) */
    OWNER_DEMOTE,
    /** 블로그 강제 폐쇄, 7일 유예 (OWNER) */
    BLOG_CLOSE,
    /** 프로필 초기화 (PROFILE) */
    PROFILE_RESET,
    /** 계정 정지 (PROFILE) */
    ACCOUNT_SUSPEND
  }

  private final ReportRepository reportRepository;
  private final AdminQueryRepository queryRepository;
  private final UserRepository userRepository;
  private final ReportService reportService;
  private final OwnerSanctionService ownerSanctionService;
  private final UserSanctionService userSanctionService;
  private final MemberSanctionService memberSanctionService;
  private final BlogCloseService closeService;
  private final NotificationService notificationService;
  private final AdminActionLogger actionLogger;
  private final Clock clock;

  public AdminReportService(
      ReportRepository reportRepository,
      AdminQueryRepository queryRepository,
      UserRepository userRepository,
      ReportService reportService,
      OwnerSanctionService ownerSanctionService,
      UserSanctionService userSanctionService,
      MemberSanctionService memberSanctionService,
      BlogCloseService closeService,
      NotificationService notificationService,
      AdminActionLogger actionLogger,
      Clock clock) {
    this.reportRepository = reportRepository;
    this.queryRepository = queryRepository;
    this.userRepository = userRepository;
    this.reportService = reportService;
    this.ownerSanctionService = ownerSanctionService;
    this.userSanctionService = userSanctionService;
    this.memberSanctionService = memberSanctionService;
    this.closeService = closeService;
    this.notificationService = notificationService;
    this.actionLogger = actionLogger;
    this.clock = clock;
  }

  /**
   * 신고 목록. page는 1부터.
   *
   * @param pending true면 대기 중, false면 처리한 신고
   */
  @Transactional(readOnly = true)
  public Page<Report> list(boolean pending, int page) {
    return queryRepository.adminReports(
        pending, ReportStatus.PENDING, PageRequest.of(Math.max(page, 1) - 1, PAGE_SIZE));
  }

  /** 신고 종류. 블로그장 기준은 지금 블로그장 */
  public Kind kind(Report report) {
    Blog blog = report.getBlog();
    if (blog == null) {
      return Kind.PROFILE;
    }
    if (report.getTargetType() == ReportTargetType.BLOG) {
      return Kind.OWNER;
    }
    Long targetUserId = reportService.targetUserId(report);
    return blog.getOwner().getId().equals(targetUserId) ? Kind.OWNER : Kind.IN_BLOG;
  }

  /** 신고 대상이 가리키는 회원(글·댓글은 작성자, 블로그는 블로그장) */
  public Long targetUserId(Report report) {
    if (report.getTargetType() == ReportTargetType.BLOG) {
      return report.getBlog() == null ? null : report.getBlog().getOwner().getId();
    }
    return reportService.targetUserId(report);
  }

  /**
   * 처리한다.
   *
   * @param days 정지(SUSPEND·ACCOUNT_SUSPEND)일 때 3·14·30, 영구는 null
   * @param reason 문제 없음이 아니면 1~500자. 대상에게 보인다
   */
  @Transactional
  public Report resolve(
      Long reportId, Long adminId, Resolution resolution, Integer days, String reason) {
    Report report =
        reportRepository
            .findById(reportId)
            .filter(r -> r.getHandlerScope() == HandlerScope.ADMIN)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (!report.isPending()) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 처리된 신고예요.");
    }
    if (resolution == null) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "처리 결과를 골라 주세요.");
    }
    Kind kind = kind(report);
    if (!kind.resolutions().contains(resolution)) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "이 신고에는 고를 수 없는 처리 결과예요.");
    }
    if (resolution != Resolution.NO_ISSUE) {
      AdminReasons.valid(reason);
    }
    ReportStatus status = apply(report, kind, resolution, adminId, days, reason);
    report.resolve(status, userRepository.getReferenceById(adminId), LocalDateTime.now(clock));
    actionLogger.log(
        adminId,
        AdminAction.RESOLVE_REPORT,
        AdminTargetType.REPORT,
        reportId,
        label(status) + (reason == null || reason.isBlank() ? "" : ". " + reason.strip()));
    notificationService.notify(
        report.getReporter().getId(),
        adminId,
        NotificationType.REPORT_RESULT,
        NotificationTargetType.REPORT,
        report.getId(),
        "신고하신 내용이 처리되었어요: " + label(status));
    return report;
  }

  private ReportStatus apply(
      Report report, Kind kind, Resolution resolution, Long adminId, Integer days, String reason) {
    Long reportId = report.getId();
    if (resolution == Resolution.NO_ISSUE) {
      return ReportStatus.NO_ISSUE;
    }
    Long targetUserId = requireUser(targetUserId(report));
    Long blogId = report.getBlog() == null ? null : report.getBlog().getId();
    return switch (kind) {
      case PROFILE ->
          switch (resolution) {
            case WARN -> {
              userSanctionService.warn(targetUserId, adminId, reason, reportId);
              yield ReportStatus.WARNED;
            }
            case PROFILE_RESET -> {
              userSanctionService.resetProfile(targetUserId, adminId, reason, reportId);
              yield ReportStatus.PROFILE_RESET;
            }
            default -> {
              userSanctionService.suspend(targetUserId, adminId, days, reason, reportId);
              yield ReportStatus.ACCOUNT_SUSPENDED;
            }
          };
      case OWNER ->
          switch (resolution) {
            case WARN ->
                ownerSanctionService.warn(blogId, adminId, reason, reportId)
                        == OwnerSanctionService.Outcome.DEMOTED
                    ? ReportStatus.OWNER_DEMOTED
                    : ReportStatus.WARNED;
            case OWNER_DEMOTE -> {
              ownerSanctionService.demote(blogId, adminId, reason, reportId);
              yield ReportStatus.OWNER_DEMOTED;
            }
            default -> {
              closeService.scheduleClose(blogId, CloseReason.ADMIN, adminId);
              actionLogger.log(
                  adminId, AdminAction.CLOSE_BLOG, AdminTargetType.BLOG, blogId, reason);
              yield ReportStatus.BLOG_CLOSED;
            }
          };
      case IN_BLOG ->
          switch (resolution) {
            case WARN -> {
              memberSanctionService.warn(blogId, targetUserId, adminId, reason, reportId);
              actionLogger.log(
                  adminId, AdminAction.MEMBER_WARN, AdminTargetType.USER, targetUserId, reason);
              yield ReportStatus.WARNED;
            }
            case SUSPEND -> {
              memberSanctionService.suspend(blogId, targetUserId, adminId, days, reason, reportId);
              actionLogger.log(
                  adminId, AdminAction.MEMBER_SUSPEND, AdminTargetType.USER, targetUserId, reason);
              yield ReportStatus.SUSPENDED;
            }
            default -> {
              memberSanctionService.kick(blogId, targetUserId, adminId, reason, reportId);
              actionLogger.log(
                  adminId, AdminAction.MEMBER_KICK, AdminTargetType.USER, targetUserId, reason);
              yield ReportStatus.KICKED;
            }
          };
    };
  }

  private static Long requireUser(Long userId) {
    if (userId == null) {
      throw new BusinessException(ErrorCode.CONFLICT, "대상을 찾을 수 없어요. 문제 없음으로 처리해 주세요.");
    }
    return userId;
  }

  /** 신고자에게 보이는 처리 결과 */
  public static String label(ReportStatus status) {
    return switch (status) {
      case PENDING -> "대기 중";
      case NO_ISSUE -> "문제 없음";
      case WARNED -> "경고";
      case SUSPENDED -> "정지";
      case KICKED -> "강제 퇴장";
      case OWNER_DEMOTED -> "블로그장 강퇴";
      case BLOG_CLOSED -> "블로그 폐쇄";
      case PROFILE_RESET -> "프로필 초기화";
      case ACCOUNT_SUSPENDED -> "계정 정지";
    };
  }
}
