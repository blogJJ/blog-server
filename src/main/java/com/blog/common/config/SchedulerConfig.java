package com.blog.common.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 예약 작업(@Scheduled)과 ShedLock (T024, SCL-03). 서버를 2대로 늘려도 04:00 배치 같은 작업이 한 서버에서 한 번만 돈다.
 *
 * <p>잠금은 V1__init.sql의 {@code shedlock} 테이블에 둔다. 서버마다 시계가 조금씩 달라도 맞게 DB 시각으로 잰다. 예약 작업에는 이렇게 붙인다:
 *
 * <pre>{@code
 * @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
 * @SchedulerLock(name = "dailyBatch", lockAtMostFor = "PT1H")
 * public void run() { ... }
 * }</pre>
 *
 * <p>lockAtMostFor는 서버가 작업 중에 죽어도 잠금이 풀리는 시간이다. 작업마다 따로 주지 않으면 30분.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerConfig {

  @Bean
  public LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .withTableName("shedlock")
            .usingDbTime()
            .build());
  }
}
