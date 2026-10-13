package com.blog.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.common.web.AssetVersions;
import com.blog.support.MySqlTestcontainersConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 화면 파일이 빨리 오는지 진짜 HTTP(Tomcat)로 확인한다 (T164): gzip, 버전 붙은 JS·CSS의 긴 캐시, 버전 없는 요청의 ETag 확인, 비회원 401의
 * 재발급 생략 헤더. MockMvc는 Tomcat의 gzip을 거치지 않는다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestcontainersConfig.class)
class StaticAssetsHttpTest {

  @LocalServerPort int port;
  @Autowired AssetVersions versions;

  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  void pageHtmlIsGzippedRevalidatedAndPointsAtVersionedAssets() throws Exception {
    HttpResponse<byte[]> res = get("/login.html", "Accept-Encoding", "gzip");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.headers().firstValue("Content-Encoding")).hasValue("gzip");
    assertThat(res.headers().firstValue("Cache-Control")).hasValue("no-cache");
    assertThat(res.headers().firstValue("Speculation-Rules"))
        .hasValue("\"/speculation-rules.json\"");
    String version = versions.version("/js/api.js").orElseThrow();
    assertThat(gunzip(res.body())).contains("src=\"/js/api.js?v=" + version + "\"");
  }

  @Test
  void versionedAssetIsCachedForAYearAndGzipped() throws Exception {
    String version = versions.version("/js/pixel-cover.js").orElseThrow();

    HttpResponse<byte[]> res = get("/js/pixel-cover.js?v=" + version, "Accept-Encoding", "gzip");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.headers().firstValue("Cache-Control"))
        .hasValue("max-age=31536000, public, immutable");
    assertThat(res.headers().firstValue("Content-Encoding")).hasValue("gzip");
    assertThat(res.body().length).isLessThan(200_000); // 원본 약 390KB
  }

  @Test
  void unversionedOrStaleAssetIsRevalidatedWithEtag() throws Exception {
    String version = versions.version("/js/api.js").orElseThrow();

    HttpResponse<byte[]> plain = get("/js/api.js", "Accept", "*/*");
    assertThat(plain.headers().firstValue("Cache-Control")).hasValue("no-cache");
    assertThat(plain.headers().firstValue("ETag")).hasValue("W/\"" + version + "\"");
    // 예전 배포의 해시로 부르면 지금 파일을 주되 오래 캐시하지 않는다
    assertThat(
            get("/js/api.js?v=000000000000", "Accept", "*/*").headers().firstValue("Cache-Control"))
        .hasValue("no-cache");

    HttpResponse<byte[]> again = get("/js/api.js", "If-None-Match", "W/\"" + version + "\"");
    assertThat(again.statusCode()).isEqualTo(304);
  }

  @Test
  void guestWithoutLoginCookieIsToldThereIsNoSessionToRefresh() throws Exception {
    HttpResponse<byte[]> guest = get("/api/me", "Accept", "application/json");
    assertThat(guest.statusCode()).isEqualTo(401);
    assertThat(guest.headers().firstValue("X-Auth-Session")).hasValue("none");

    // 로그인 쿠키가 있는데 401이면(토큰 만료 등) 화면이 재발급을 시도해야 하므로 붙이지 않는다
    HttpResponse<byte[]> expired = get("/api/me", "Cookie", "access_token=expired");
    assertThat(expired.statusCode()).isEqualTo(401);
    assertThat(expired.headers().firstValue("X-Auth-Session")).isEmpty();
  }

  @Test
  void speculationRulesAreServedWithTheirMediaType() throws Exception {
    HttpResponse<byte[]> res = get("/speculation-rules.json", "Accept", "*/*");

    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.headers().firstValue("Content-Type"))
        .hasValueSatisfying(v -> assertThat(v).startsWith("application/speculationrules+json"));
    assertThat(new String(res.body(), StandardCharsets.UTF_8))
        .contains("\"prefetch\"")
        .contains("/blog/*/admin");
  }

  private HttpResponse<byte[]> get(String path, String header, String value) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header(header, value)
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private static String gunzip(byte[] body) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
