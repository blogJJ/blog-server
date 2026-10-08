package com.blog.blog.api;

import com.blog.common.security.AuthUser;
import com.blog.social.domain.Report;
import com.blog.social.service.ReportService;
import com.blog.social.service.ReportService.Resolution;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 블로그 안 신고 목록과 처리 (T080, BLG-13). 블로그장이 처리하는 신고(handler_scope = BLOG_OWNER)만. */
@RestController
@RequestMapping("/api/blogs/{blogId}/reports")
public class BlogReportController {

  private final ReportService reportService;

  public BlogReportController(ReportService reportService) {
    this.reportService = reportService;
  }

  /**
   * @param days 정지일 때 3·14·30, 영구는 null
   */
  public record ResolveForm(Resolution resolution, Integer days, String reason) {}

  public record ReportRow(
      Long id,
      String targetType,
      Long targetId,
      Long targetUserId,
      String reason,
      String detail,
      String snapshot,
      String reporter,
      LocalDateTime createdAt) {}

  @GetMapping
  @PreAuthorize("@blogAuthz.canHandleReports(#blogId)")
  @Transactional(readOnly = true)
  public List<ReportRow> pending(@PathVariable Long blogId) {
    return reportService.pendingForBlog(blogId).stream().map(this::row).toList();
  }

  @PostMapping("/{reportId}/resolve")
  @PreAuthorize("@blogAuthz.canHandleReports(#blogId)")
  public ResponseEntity<Void> resolve(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @PathVariable Long reportId,
      @RequestBody ResolveForm form) {
    reportService.resolveInBlog(
        blogId, reportId, user.id(), form.resolution(), form.days(), form.reason());
    return ResponseEntity.noContent().build();
  }

  private ReportRow row(Report r) {
    return new ReportRow(
        r.getId(),
        r.getTargetType().name(),
        r.getTargetId(),
        reportService.targetUserId(r),
        r.getReason().name(),
        r.getDetail(),
        r.getTargetSnapshot(),
        r.getReporter().getNickname(),
        r.getCreatedAt());
  }
}
