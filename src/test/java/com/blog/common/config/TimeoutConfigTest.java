package com.blog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 5초 넘는 쿼리는 DB에서 끊기고 "다시 시도" 응답이 나가는지 (T009, D-48, SC-001). */
@IntegrationTest
@Import(TimeoutConfigTest.Slow.class)
class TimeoutConfigTest {

  @Autowired MockMvc mockMvc;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void transactionsDefaultToFiveSeconds() {
    assertThat(((AbstractPlatformTransactionManager) transactionManager).getDefaultTimeout())
        .isEqualTo(5);
  }

  @Test
  void slowQueryIsCutAndAsksToRetry() throws Exception {
    long started = System.nanoTime();

    mockMvc
        .perform(get("/api/test/slow"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("REQUEST_TIMEOUT"))
        .andExpect(jsonPath("$.message").value("응답이 늦어지고 있어요. 잠시 뒤 다시 시도해 주세요."));

    long seconds = (System.nanoTime() - started) / 1_000_000_000;
    assertThat(seconds).isLessThan(8);
  }

  @TestConfiguration
  @Import({SlowService.class, SlowController.class})
  static class Slow {}

  @Service
  static class SlowService {
    private final JdbcTemplate jdbc;

    SlowService(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Integer sleep() {
      return jdbc.queryForObject("SELECT SLEEP(10)", Integer.class);
    }
  }

  @RestController
  static class SlowController {
    private final SlowService service;

    SlowController(SlowService service) {
      this.service = service;
    }

    @GetMapping("/api/test/slow")
    Integer slow() {
      return service.sleep();
    }
  }
}
