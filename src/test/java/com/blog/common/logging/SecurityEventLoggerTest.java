package com.blog.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecurityEventLoggerTest {

  @Test
  void removesLineBreaksSoLogLinesCannotBeForged() {
    assertThat(SecurityEventLogger.sanitize("/api/x\r\nINFO fake")).isEqualTo("/api/x__INFO fake");
    assertThat(SecurityEventLogger.sanitize(null)).isEmpty();
  }
}
