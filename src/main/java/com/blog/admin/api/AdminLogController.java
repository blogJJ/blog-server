package com.blog.admin.api;

import com.blog.admin.domain.AdminActionLog;
import com.blog.admin.repository.AdminActionLogRepository;
import com.blog.admin.service.AdminSearch;
import com.blog.common.web.PageResponse;
import java.time.LocalDateTime;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 활동 기록 보기 (T126, ADM-06): 누가 언제 무엇을 했는지. 최신순 */
@RestController
public class AdminLogController {

  public record LogRow(
      Long id,
      Long adminId,
      String admin,
      String action,
      String targetType,
      Long targetId,
      String reason,
      LocalDateTime createdAt) {}

  private final AdminActionLogRepository logRepository;

  public AdminLogController(AdminActionLogRepository logRepository) {
    this.logRepository = logRepository;
  }

  @GetMapping("/api/admin/logs")
  @Transactional(readOnly = true)
  public PageResponse<LogRow> logs(@RequestParam(defaultValue = "1") int page) {
    return PageResponse.of(
        logRepository.findLatest(AdminSearch.page(page)),
        list -> list.stream().map(AdminLogController::row).toList());
  }

  private static LogRow row(AdminActionLog l) {
    return new LogRow(
        l.getId(),
        l.getAdmin().getId(),
        l.getAdmin().getNickname(),
        l.getAction().name(),
        l.getTargetType().name(),
        l.getTargetId(),
        l.getReason(),
        l.getCreatedAt());
  }
}
