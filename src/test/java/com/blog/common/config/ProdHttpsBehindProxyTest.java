package com.blog.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.auth.service.SocialClientRegistrations;
import com.blog.common.web.SiteUrl;
import com.blog.support.MySqlTestcontainersConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 운영처럼 프록시 뒤에서 https를 지키는지 진짜 HTTP로 확인한다 (T160). MockMvc는 Tomcat이 X-Forwarded-Proto를 읽는 부분(native)을
 * 거치지 않는다. 테스트 요청은 127.0.0.1에서 오므로 Tomcat이 믿는 프록시로 본다(운영의 프록시 컨테이너도 사설 IP).
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.password=test-db-password",
      "spring.mail.password=test-mail-password",
      "app.turnstile.secret-key=test-turnstile-secret",
      "logging.file.path=build/test-logs",
      "app.social.google.client-id=test-google-id",
      "app.social.google.client-secret=test-google-secret"
    })
@ActiveProfiles({"test", "prod"})
@Import(MySqlTestcontainersConfig.class)
class ProdHttpsBehindProxyTest {

  static final String SITE = "https://jj-blog.java21.net";

  @LocalServerPort int port;
  @Autowired SiteUrl siteUrl;
  @Autowired SocialClientRegistrations socialRegistrations;

  private final HttpClient client =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    headers.forEach(b::header);
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static Map<String, String> forwarded(String proto) {
    return Map.of("X-Forwarded-Proto", proto, "X-Forwarded-Host", "jj-blog.java21.net");
  }

  @Test
  void httpsThroughProxySendsHstsWithoutSubdomains() throws Exception {
    HttpResponse<String> res = get("/api/blogs", forwarded("https"));

    assertThat(res.statusCode()).isEqualTo(200);
    // 공용 학교 도메인의 하위 주소라 includeSubDomains·preload는 붙이지 않는다
    assertThat(res.headers().allValues("Strict-Transport-Security"))
        .containsExactly("max-age=31536000");
  }

  @Test
  void httpThroughProxyIsRedirectedToHttps() throws Exception {
    HttpResponse<String> res = get("/api/blogs?page=1", forwarded("http"));

    assertThat(res.statusCode()).isBetween(301, 308);
    assertThat(res.headers().firstValue("Location").orElseThrow())
        .isEqualTo(SITE + "/api/blogs?page=1");
    // http 응답에는 HSTS를 붙이지 않는다(브라우저도 무시한다)
    assertThat(res.headers().firstValue("Strict-Transport-Security")).isEmpty();
  }

  @Test
  void requestWithoutProxyHeadersIsRedirectedToHttps() throws Exception {
    HttpResponse<String> res = get("/", Map.of());

    assertThat(res.statusCode()).isBetween(301, 308);
    assertThat(res.headers().firstValue("Location").orElseThrow()).startsWith("https://");
  }

  @Test
  void healthStaysOnHttpForProbes() throws Exception {
    assertThat(get("/actuator/health", Map.of()).statusCode()).isEqualTo(200);
  }

  @Test
  void appRedirectsNeverDowngradeToHttp() throws Exception {
    // 로그인하지 않고 관리자 화면에 오면 로그인 화면으로 보낸다. Location이 http://로 바뀌면 안 된다
    HttpResponse<String> res = get("/admin", forwarded("https"));

    assertThat(res.statusCode()).isBetween(301, 308);
    String location = res.headers().firstValue("Location").orElseThrow();
    assertThat(location).doesNotStartWith("http://");
    assertThat(location)
        .satisfiesAnyOf(
            l -> assertThat(l).startsWith("/login.html"),
            l -> assertThat(l).startsWith(SITE + "/login.html"));
  }

  @Test
  void socialLoginReturnAddressIsFixedHttpsSite() throws Exception {
    assertThat(siteUrl.configured()).isEqualTo(SITE);
    assertThat(socialRegistrations.findByRegistrationId("google").getRedirectUri())
        .isEqualTo(SITE + "/login/oauth2/code/{registrationId}");

    // 프록시가 다른 Host를 보내도 구글에 알리는 돌아오는 주소는 https 사이트 주소다
    HttpResponse<String> res =
        get("/oauth2/authorization/google", Map.of("X-Forwarded-Proto", "https"));
    assertThat(res.statusCode()).isBetween(301, 308);
    assertThat(res.headers().firstValue("Location").orElseThrow())
        .contains("redirect_uri=" + SITE + "/login/oauth2/code/google&")
        .doesNotContain("redirect_uri=http:");
  }
}
