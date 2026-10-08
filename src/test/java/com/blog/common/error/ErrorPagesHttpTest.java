package com.blog.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.support.MySqlTestcontainersConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 화면 주소의 오류는 안내 화면, API 오류는 JSON (T136, OPS-02). 오류 화면은 서블릿 오류 처리로 나가므로 진짜 HTTP로 확인한다. 공통 화면 파일이
 * 열리는지도 본다 (T027).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({MySqlTestcontainersConfig.class, ErrorPagesHttpTest.Boom.class})
class ErrorPagesHttpTest {

  @LocalServerPort int port;

  @Test
  void unknownPageShowsNotFoundPage() throws Exception {
    HttpResponse<String> response = get("/no-such-page", "text/html");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("text/html"));
    assertThat(response.body()).contains("페이지를 찾을 수 없어요").contains("메인으로 가기");
  }

  @Test
  void unknownApiStaysJson() throws Exception {
    HttpResponse<String> response = get("/api/no-such-api", "application/json");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
  }

  @Test
  void pageErrorShowsErrorIdWithoutInternals() throws Exception {
    HttpResponse<String> response = get("/test-page-boom", "text/html");

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.body()).contains("일시적인 오류가 생겼어요");
    String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
    assertThat(response.body()).contains("<span data-error-id>" + requestId + "</span>");
    assertThat(response.body())
        .doesNotContain("IllegalStateException")
        .doesNotContain("secret-sql");
  }

  @Test
  void commonScreenFilesAreServed() throws Exception {
    for (String path : new String[] {"/", "/js/api.js", "/js/layout.js", "/css/common.css"}) {
      assertThat(get(path, "*/*").statusCode()).as(path).isEqualTo(200);
    }
  }

  private HttpResponse<String> get(String path, String accept) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", accept)
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  @TestConfiguration
  @Import(BoomController.class)
  static class Boom {}

  @Controller
  static class BoomController {
    @GetMapping("/test-page-boom")
    String boom() {
      throw new IllegalStateException("secret-sql SELECT * FROM users");
    }
  }
}
