package com.blog.batch;

import com.blog.blog.service.BlogCloseService;
import com.blog.blog.service.TransferService;
import java.time.Clock;
import java.time.LocalDateTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 04:00 배치 (T089, T090, BLG-09, D-12, D-83, data-model.md 04:00 배치). ShedLock으로 서버가 여러 대여도 한
 * 서버에서 한 번만 돈다 (SCL-03).
 *
 * <ol>
 *   <li>폐쇄 예정 시각이 지난 블로그 → CLOSED
 *   <li>폐쇄까지 3일·1일 남은 블로그 → 아직 안 보냈으면 알림
 *   <li>7일 지난 대기 위임 요청 → EXPIRED, 블로그장에게 알림
 *   <li>④~⑦ 30일·1년 보관 기간이 지난 데이터 정리 ({@link RetentionCleanup})
 * </ol>
 *
 * <p>서버가 꺼졌던 날이 있으면 다음 배치가 밀린 폐쇄와 알림을 처리한다. 알림은 발송 기록으로 한 번만 보낸다. 단계마다 실패해도 다음 단계는 한다.
 */
@Component
public class DailyBatchJob {

  private static final Logger log = LoggerFactory.getLogger(DailyBatchJob.class);

  private final BlogCloseService closeService;
  private final TransferService transferService;
  private final RetentionCleanup retentionCleanup;
  private final Clock clock;

  public DailyBatchJob(
      BlogCloseService closeService,
      TransferService transferService,
      RetentionCleanup retentionCleanup,
      Clock clock) {
    this.closeService = closeService;
    this.transferService = transferService;
    this.retentionCleanup = retentionCleanup;
    this.clock = clock;
  }

  @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
  @SchedulerLock(name = "dailyBatch", lockAtMostFor = "PT1H")
  public void run() {
    LocalDateTime now = LocalDateTime.now(clock);
    log.info("daily batch start at {}", now);
    try {
      int closed = closeService.closeDue(now);
      int reminded = closeService.sendReminders(now);
      log.info("daily batch closed={} reminders={}", closed, reminded);
    } catch (RuntimeException e) {
      log.error("daily batch close step failed", e);
    }
    try {
      log.info("daily batch transfers expired={}", transferService.expireDue(now));
    } catch (RuntimeException e) {
      log.error("daily batch transfer step failed", e);
    }
    retentionCleanup.run(now);
    log.info("daily batch done");
  }
}
