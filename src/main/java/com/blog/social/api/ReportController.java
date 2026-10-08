package com.blog.social.api;

import com.blog.common.security.AuthUser;
import com.blog.social.domain.Report;
import com.blog.social.service.ReportService;
import com.blog.social.service.ReportService.ReportForm;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 신고 접수 (T075, SOC-06). */
@RestController
public class ReportController {

  private final ReportService reportService;

  public ReportController(ReportService reportService) {
    this.reportService = reportService;
  }

  /** 누가 처리할지(블로그장 또는 메인 관리자)를 같이 돌려준다 */
  public record Created(Long id, String handlerScope) {}

  @PostMapping("/api/reports")
  @ResponseStatus(HttpStatus.CREATED)
  public Created create(@AuthenticationPrincipal AuthUser user, @RequestBody ReportForm form) {
    Report report = reportService.create(user.id(), form);
    return new Created(report.getId(), report.getHandlerScope().name());
  }
}
