package com.blog.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** API 요청을 IP별로 막고 429를 주는지 (T022, SEC-03). */
class RateLimitFilterTest {

  RateLimitFilter filter =
      new RateLimitFilter(
          new InMemoryRateLimiter(Clock.systemUTC()), JsonMapper.builder().build(), 2);

  @Test
  void blocksThirdApiRequestFromSameIp() throws Exception {
    assertThat(call("/api/blogs", "203.0.113.5").getStatus()).isEqualTo(200);
    assertThat(call("/api/blogs", "203.0.113.5").getStatus()).isEqualTo(200);

    MockHttpServletResponse blocked = call("/api/blogs", "203.0.113.5");

    assertThat(blocked.getStatus()).isEqualTo(429);
    assertThat(blocked.getHeader("Retry-After")).isNotBlank();
    assertThat(blocked.getContentAsString()).contains("\"code\":\"TOO_MANY_REQUESTS\"");
    // 다른 IP는 영향 없음
    assertThat(call("/api/blogs", "198.51.100.7").getStatus()).isEqualTo(200);
  }

  @Test
  void pagesAndStaticFilesAreNotLimited() throws Exception {
    for (int i = 0; i < 5; i++) {
      assertThat(call("/index.html", "203.0.113.9").getStatus()).isEqualTo(200);
    }
  }

  private MockHttpServletResponse call(String uri, String ip) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.setRemoteAddr(ip);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }
}
