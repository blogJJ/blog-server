package com.blog.admin.api;

import com.blog.admin.service.AdminReportService;
import com.blog.admin.service.AdminReportService.Kind;
import com.blog.admin.service.AdminReportService.Resolution;
import com.blog.common.security.AuthUser;
import com.blog.common.web.PageResponse;
import com.blog.social.domain.Report;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 메인 관리자 신고 처리 (T123, ADM-04, ADM-08). {@code handler_scope = ADMIN}인 신고만. 목록의 각 줄에 신고 종류와 고를 수 있는
 * 처리 결과를 같이 준다.
 */
@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {

  /**
   * @param days 정지(SUSPEND·ACCOUNT_SUSPEND)일 때 3·14·30, 영구는 null
   */
  public record ResolveForm(Resolution resolution, Integer days, String reason) {}

  public record ReportRow(
      Long id,
      String kind,
      List<Resolution> resolutions,
      String targetType,
      Long targetId,
      Long targetUserId,
      Long blogId,
      String blogSlug,
      String blogName,
      String reason,
      String detail,
      String snapshot,
      String reporter,
      String status,
      String handledBy,
      LocalDateTime handledAt,
      LocalDateTime createdAt) {}

  private final AdminReportService reportService;

  public AdminReportController(AdminReportService reportService) {
    this.reportService = reportService;
  }

  /**
   * @param status PENDING(기본)이면 대기 중, DONE이면 처리한 신고
   */
  @GetMapping
  @Transactional(readOnly = true)
  public PageResponse<ReportRow> list(
      @RequestParam(defaultValue = "PENDING") String status,
      @RequestParam(defaultValue = "1") int page) {
    return PageResponse.of(
        reportService.list(!"DONE".equalsIgnoreCase(status), page),
        list -> list.stream().map(this::row).toList());
  }

  @PostMapping("/{reportId}/resolve")
  public ResponseEntity<Void> resolve(
      @AuthenticationPrincipal AuthUser admin,
      @PathVariable Long reportId,
      @RequestBody ResolveForm form) {
    reportService.resolve(reportId, admin.id(), form.resolution(), form.days(), form.reason());
    return ResponseEntity.noContent().build();
  }

  private ReportRow row(Report r) {
    Kind kind = reportService.kind(r);
    return new ReportRow(
        r.getId(),
        kind.name(),
        r.isPending() ? kind.resolutions() : List.of(),
        r.getTargetType().name(),
        r.getTargetId(),
        reportService.targetUserId(r),
        r.getBlog() == null ? null : r.getBlog().getId(),
        r.getBlog() == null ? null : r.getBlog().getSlug(),
        r.getBlog() == null ? null : r.getBlog().getName(),
        r.getReason().name(),
        r.getDetail(),
        r.getTargetSnapshot(),
        r.getReporter().getNickname(),
        r.getStatus().name(),
        r.getHandledBy() == null ? null : r.getHandledBy().getNickname(),
        r.getHandledAt(),
        r.getCreatedAt());
  }
}
