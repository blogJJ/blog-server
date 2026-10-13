package com.blog.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

/**
 * Turnstile 서버 검증 (T033, SEC-13, D-79). Cloudflare 대신 가짜 서버로 보낸다. 주소만 바꾸고 3초 시간 제한은 그대로 쓴다.
 * Cloudflare에 닿지 못하거나 답이 이상하면 통과시키지 않는다(실패로 본다).
 */
class TurnstileVerifierTest {

  private HttpServer server;
  private final AtomicReference<String> lastBody = new AtomicReference<>();
  private final AtomicReference<String> lastPath = new AtomicReference<>();
  private final AtomicReference<String> lastContentType = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("Cloudflare가 success=true로 답하면 통과하고, 비밀 키·토큰·접속 IP를 폼으로 보낸다 (SEC-13)")
  void passesWhenCloudflareSaysSuccess() throws IOException {
    start(200, "application/json", "{\"success\":true}", 0);

    assertThat(verifier("secret-1").verify("token-1", "203.0.113.7")).isTrue();
    assertThat(lastPath.get()).isEqualTo("/turnstile/v0/siteverify");
    assertThat(lastContentType.get()).startsWith("application/x-www-form-urlencoded");
    assertThat(lastBody.get())
        .contains("secret=secret-1")
        .contains("response=token-1")
        .contains("remoteip=203.0.113.7");
  }

  @Test
  @DisplayName("접속 IP를 모르면 remoteip 없이 보낸다")
  void omitsRemoteIpWhenUnknown() throws IOException {
    start(200, "application/json", "{\"success\":true}", 0);

    assertThat(verifier("secret-1").verify("token-1", null)).isTrue();
    assertThat(lastBody.get()).doesNotContain("remoteip");
  }

  @Test
  @DisplayName("Cloudflare가 success=false로 답하면 막는다")
  void rejectsWhenCloudflareSaysFailure() throws IOException {
    start(
        200,
        "application/json",
        "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}",
        0);

    assertThat(verifier("secret-1").verify("bad-token", "203.0.113.7")).isFalse();
  }

  @Test
  @DisplayName("답에 success가 없거나 true가 아니면 막는다 (이상한 답은 실패)")
  void rejectsWhenSuccessIsMissingOrNotTrue() throws IOException {
    start(200, "application/json", "{\"success\":\"true\"}", 0);
    assertThat(verifier("secret-1").verify("token-1", null)).isFalse();
    server.stop(0);

    start(200, "application/json", "{}", 0);
    assertThat(verifier("secret-1").verify("token-1", null)).isFalse();
    server.stop(0);

    // 본문이 비어 있는 답
    start(200, "application/json", "", 0);
    assertThat(verifier("secret-1").verify("token-1", null)).isFalse();
  }

  @Test
  @DisplayName("검증 서버가 5xx로 답하면 막는다 (fail-closed)")
  void rejectsWhenVerifyServerErrors() throws IOException {
    start(500, "application/json", "{\"success\":true}", 0);

    assertThat(verifier("secret-1").verify("token-1", "203.0.113.7")).isFalse();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("검증 서버가 JSON이 아닌 답(점검 페이지 등)을 주면 막는다")
  void rejectsWhenResponseIsNotJson() throws IOException {
    start(200, "text/html", "<html>maintenance</html>", 0);

    assertThat(verifier("secret-1").verify("token-1", null)).isFalse();
  }

  @Test
  @DisplayName("검증 서버가 3초 안에 답하지 않으면 기다리지 않고 막는다 (fail-closed)")
  void rejectsWhenVerifyServerTimesOut() throws IOException {
    start(200, "application/json", "{\"success\":true}", 4_500);

    long started = System.nanoTime();
    assertThat(verifier("secret-1").verify("token-1", null)).isFalse();
    long tookMillis = (System.nanoTime() - started) / 1_000_000;
    assertThat(tookMillis).isLessThan(4_500);
  }

  @Test
  @DisplayName("검증 서버에 닿지 못하면(연결 거부) 막는다 (fail-closed)")
  void rejectsWhenVerifyServerIsUnreachable() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    assertThat(verifier("secret-1", closedPort).verify("token-1", null)).isFalse();
  }

  @Test
  @DisplayName("토큰이 비었거나 비밀 키가 없으면 Cloudflare를 부르지 않고 막는다")
  void rejectsWithoutCallingWhenTokenOrSecretIsMissing() throws IOException {
    start(200, "application/json", "{\"success\":true}", 0);

    assertThat(verifier("secret-1").verify(null, null)).isFalse();
    assertThat(verifier("secret-1").verify("  ", null)).isFalse();
    assertThat(verifier("").verify("token-1", null)).isFalse();
    assertThat(verifier(null).verify("token-1", null)).isFalse();
    assertThat(calls.get()).isZero();
  }

  @Test
  @DisplayName("화면 위젯용 공개 키를 그대로 돌려준다")
  void exposesSiteKeyForWidget() {
    assertThat(new TurnstileVerifier("site-key-1", "secret").getSiteKey()).isEqualTo("site-key-1");
  }

  // ---------- 도우미 ----------

  private TurnstileVerifier verifier(String secret) {
    return verifier(secret, server.getAddress().getPort());
  }

  /** 진짜 검증기를 만들고, Cloudflare 주소로 가는 요청만 가짜 서버로 돌린다. 요청 공장(3초 제한)은 그대로다. */
  private static TurnstileVerifier verifier(String secret, int port) {
    TurnstileVerifier verifier = new TurnstileVerifier("site-key", secret);
    RestClient original = (RestClient) ReflectionTestUtils.getField(verifier, "restClient");
    ClientHttpRequestInterceptor redirect =
        (request, body, execution) ->
            execution.execute(
                new HttpRequestWrapper(request) {
                  @Override
                  public URI getURI() {
                    URI uri = super.getURI();
                    assertThat(uri.toString()).isEqualTo(TurnstileVerifier.VERIFY_URL);
                    return URI.create("http://127.0.0.1:" + port + uri.getPath());
                  }
                },
                body);
    ReflectionTestUtils.setField(
        verifier, "restClient", original.mutate().requestInterceptor(redirect).build());
    return verifier;
  }

  // 시간 초과를 시험하려고 일부러 늦게 답하는 가짜 서버다. 기다리는 쪽이 아니라 늦게 답하는 쪽이라 sleep이 맞다
  @SuppressWarnings("java:S2925")
  private void start(int status, String contentType, String body, long delayMillis)
      throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          lastPath.set(exchange.getRequestURI().getPath());
          lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          lastBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          if (delayMillis > 0) {
            try {
              Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", contentType);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }
}
