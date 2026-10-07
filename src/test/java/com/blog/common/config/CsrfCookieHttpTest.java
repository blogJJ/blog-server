package com.blog.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.support.MySqlTestcontainersConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** XSRF-TOKEN 쿠키는 응답을 보낼 때 써지므로 MockMvc로는 보이지 않는다. 진짜 HTTP로 확인한다 (SEC-10). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestcontainersConfig.class)
class CsrfCookieHttpTest {

  @LocalServerPort int port;

  @Test
  void everyResponseGivesReadableXsrfCookie() throws Exception {
    HttpResponse<Void> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/blogs"))
                    .build(),
                HttpResponse.BodyHandlers.discarding());

    List<String> cookies = response.headers().allValues("Set-Cookie");
    assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("XSRF-TOKEN="));
    // api.js가 읽어 X-XSRF-TOKEN 헤더로 보내야 하므로 HttpOnly가 아니다
    assertThat(cookies)
        .filteredOn(c -> c.startsWith("XSRF-TOKEN="))
        .allSatisfy(c -> assertThat(c).doesNotContain("HttpOnly"));
  }
}
