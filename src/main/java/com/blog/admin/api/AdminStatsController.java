package com.blog.admin.api;

import com.blog.admin.service.AdminStatsService;
import com.blog.admin.service.AdminStatsService.Stats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 통계 (T124, ADM-05): 회원 수, 블로그 수, 일별 가입·글 수 */
@RestController
public class AdminStatsController {

  private final AdminStatsService statsService;

  public AdminStatsController(AdminStatsService statsService) {
    this.statsService = statsService;
  }

  /**
   * @param days 최근 며칠(7~90). 기본 30
   */
  @GetMapping("/api/admin/stats")
  public Stats stats(@RequestParam(required = false) Integer days) {
    return statsService.stats(days);
  }
}
