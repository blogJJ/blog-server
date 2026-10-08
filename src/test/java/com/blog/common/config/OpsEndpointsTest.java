package com.blog.common.config;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** 기본 설정(운영 기준)에서 상태 확인·Swagger·요청 ID가 정한 대로 동작하는지 (T137, T138, T145). */
@IntegrationTest
class OpsEndpointsTest {

  @Autowired MockMvc mvc;

  @Test
  void healthShowsOnlyUpOrDown() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(jsonPath("$.details").doesNotExist());
  }

  @Test
  void otherActuatorEndpointsAreClosed() throws Exception {
    // denyAll: 비회원은 401, 회원이라도 열리지 않는다
    mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/health/db")).andExpect(status().isUnauthorized());
  }

  @Test
  void swaggerIsClosedByDefault() throws Exception {
    mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
  }

  @Test
  void everyResponseCarriesRequestId() throws Exception {
    mvc.perform(get("/api/nothing-here"))
        .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f]{8}")));
  }
}
