package com.blog.blog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.blog.domain.Mascot;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Claude 호출을 가짜 서버로 확인한다. 진짜 API는 부르지 않는다 (BLG-14, D-116). */
class ClaudeMascotClientTest {

  private HttpServer server;
  private final AtomicReference<String> lastBody = new AtomicReference<>();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void disabledWithoutKey() {
    ClaudeMascotClient client = new ClaudeMascotClient("", "claude-opus-5-5", "");
    assertThat(client.enabled()).isFalse();
    assertThat(client.choose("민준의 식탁", List.of("집밥"))).isEmpty();
  }

  @Test
  void readsStructuredChoiceAndSendsOnlyNameAndTags() throws IOException {
    start(200, response("{\"mascot\":\"OWL\"}", "end_turn"));
    ClaudeMascotClient client = client();

    assertThat(client.choose("공부방", List.of("자격증", "스터디"))).contains(Mascot.OWL);
    assertThat(lastBody.get())
        .contains("claude-opus-5-5")
        .contains("공부방")
        .contains("자격증, 스터디")
        .contains("output_config");
  }

  @Test
  void refusalOrCutOffGivesEmpty() throws IOException {
    start(200, response("", "refusal"));
    assertThat(client().choose("블로그", List.of())).isEmpty();
  }

  @Test
  void serverErrorGivesEmpty() throws IOException {
    start(
        400,
        "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}");
    assertThat(client().choose("블로그", List.of("여행"))).isEmpty();
  }

  @Test
  void unreachableServerGivesEmpty() {
    ClaudeMascotClient client =
        new ClaudeMascotClient("test-key", "claude-opus-5-5", "http://127.0.0.1:1");
    assertThat(client.choose("블로그", List.of("여행"))).isEmpty();
  }

  private ClaudeMascotClient client() {
    return new ClaudeMascotClient(
        "test-key", "claude-opus-5-5", "http://127.0.0.1:" + server.getAddress().getPort());
  }

  private void start(int status, String body) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/messages",
        ex -> {
          lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] out = body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(status, out.length);
          ex.getResponseBody().write(out);
          ex.close();
        });
    server.start();
  }

  private static String response(String text, String stopReason) {
    String content =
        text.isEmpty()
            ? "[]"
            : "[{\"type\":\"text\",\"text\":\"" + text.replace("\"", "\\\"") + "\"}]";
    return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
        + "\"model\":\"claude-opus-5-5\",\"content\":"
        + content
        + ",\"stop_reason\":\""
        + stopReason
        + "\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
  }
}
