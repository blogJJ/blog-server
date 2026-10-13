package com.blog.blog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.blog.domain.CoverScene;
import com.blog.blog.domain.Mascot;
import com.blog.blog.domain.MascotDesign;
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
    start(
        200,
        response(
            "{\"base\":\"OWL\",\"tone\":\"SKY\",\"hat\":\"BERET\",\"charm\":\"STAR\","
                + "\"scene\":\"STUDY\",\"time\":\"NIGHT\",\"weather\":\"RAIN\",\"palette\":\"MINT\"}",
            "end_turn"));
    ClaudeMascotClient client = client();

    assertThat(client.choose("공부방", List.of("자격증", "스터디")))
        .contains(
            new ClaudeMascotClient.Art(
                new MascotDesign(
                    Mascot.OWL,
                    MascotDesign.Tone.SKY,
                    MascotDesign.Hat.BERET,
                    MascotDesign.Charm.STAR),
                new CoverScene(
                        CoverScene.Scene.STUDY,
                        CoverScene.Time.NIGHT,
                        CoverScene.Weather.RAIN,
                        CoverScene.Palette.MINT,
                        0)
                    .withLayers(null, null)));
    assertThat(lastBody.get())
        .contains("claude-opus-5-5")
        .contains("공부방")
        .contains("자격증, 스터디")
        .contains("output_config");
  }

  @Test
  void readsNewSceneAndPropTheme() throws IOException {
    start(
        200,
        response(
            "{\"base\":\"BIRD\",\"tone\":\"ORIGINAL\",\"hat\":\"NONE\",\"charm\":\"NONE\","
                + "\"scene\":\"CAMPSITE\",\"time\":\"NIGHT\",\"weather\":\"CLEAR\","
                + "\"palette\":\"FOREST\",\"prop\":\"STAR\"}",
            "end_turn"));

    ClaudeMascotClient.Art art = client().choose("불멍 일지", List.of("캠핑", "별")).orElseThrow();

    assertThat(art.cover())
        .isEqualTo(
            new CoverScene(
                    CoverScene.Scene.CAMPSITE,
                    CoverScene.Time.NIGHT,
                    CoverScene.Weather.CLEAR,
                    CoverScene.Palette.FOREST,
                    0,
                    CoverScene.Prop.STAR)
                .withLayers(null, null));
    // 고를 수 있는 장면·색·소품 주제를 모두 알려 준다
    for (CoverScene.Scene scene : CoverScene.Scene.values()) {
      assertThat(ClaudeMascotClient.SYSTEM).contains(scene.name());
      assertThat(lastBody.get()).contains(scene.name());
    }
    for (CoverScene.Prop prop : CoverScene.Prop.values()) {
      assertThat(lastBody.get()).contains(prop.name());
    }
    for (CoverScene.Backdrop b : CoverScene.Backdrop.values()) {
      assertThat(lastBody.get()).contains(b.name());
    }
    for (CoverScene.Event e : CoverScene.Event.values()) {
      assertThat(lastBody.get()).contains(e.name());
    }
    assertThat(lastBody.get()).contains("PASTEL").contains("OCEAN");
    assertThat(ClaudeMascotClient.SYSTEM).contains("not instructions");
  }

  @Test
  void readsAlternativeBases() throws IOException {
    // D-143: 바탕과 함께 어울리는 다른 바탕을 최대 2개 받는다. 바탕과 같은 값과 겹치는 값은 뺀다
    start(
        200,
        response(
            "{\"base\":\"PENCIL\",\"alternatives\":[\"PENCIL\",\"OWL\",\"OWL\",\"HEDGEHOG\",\"CAT\"],"
                + "\"tone\":\"ORIGINAL\",\"hat\":\"NONE\",\"charm\":\"NONE\","
                + "\"scene\":\"STUDY\",\"time\":\"DAY\",\"weather\":\"CLEAR\","
                + "\"palette\":\"WARM\"}",
            "end_turn"));
    ClaudeMascotClient.Art art = client().choose("글방", List.of("에세이")).orElseThrow();
    assertThat(art.mascot().base()).isEqualTo(Mascot.PENCIL);
    assertThat(art.bases()).containsExactly(Mascot.PENCIL, Mascot.OWL, Mascot.HEDGEHOG);
    // 고를 수 있는 바탕을 모두 알려 주고, 답 형식에 다른 바탕 칸이 있다
    for (Mascot m : Mascot.values()) {
      assertThat(lastBody.get()).contains(m.name());
    }
    assertThat(lastBody.get()).contains("alternatives");
    assertThat(ClaudeMascotClient.SYSTEM).contains("PENCIL").contains("PLANT");
  }

  @Test
  void missingAlternativesMeansOnlyBase() throws IOException {
    start(
        200,
        response(
            "{\"base\":\"CAT\",\"tone\":\"ORIGINAL\",\"hat\":\"NONE\",\"charm\":\"NONE\","
                + "\"scene\":\"BEDROOM\",\"time\":\"DAY\",\"weather\":\"CLEAR\","
                + "\"palette\":\"PASTEL\"}",
            "end_turn"));
    assertThat(client().choose("방", List.of()).orElseThrow().bases()).containsExactly(Mascot.CAT);
  }

  @Test
  void missingPropMeansNoTheme() throws IOException {
    start(
        200,
        response(
            "{\"base\":\"CAT\",\"tone\":\"ORIGINAL\",\"hat\":\"NONE\",\"charm\":\"NONE\","
                + "\"scene\":\"BEDROOM\",\"time\":\"DAY\",\"weather\":\"CLEAR\","
                + "\"palette\":\"PASTEL\"}",
            "end_turn"));
    CoverScene cover = client().choose("방", List.of("자취")).orElseThrow().cover();
    assertThat(cover.scene()).isEqualTo(CoverScene.Scene.BEDROOM);
    assertThat(cover.prop()).isNull();
    // Claude가 고른 장면은 섞기 모드다. 배경·계절은 저장할 때 정한다
    assertThat(cover.value()).isEqualTo("bedroom.day.clear.pastel.0.-.~");
  }

  @Test
  void readsBackdropAndEvent() throws IOException {
    start(
        200,
        response(
            "{\"base\":\"CAT\",\"tone\":\"ORIGINAL\",\"hat\":\"NONE\",\"charm\":\"NONE\","
                + "\"scene\":\"BEDROOM\",\"time\":\"NIGHT\",\"weather\":\"SNOW\","
                + "\"palette\":\"BERRY\",\"prop\":\"CAT\",\"backdrop\":\"SNOW\","
                + "\"event\":\"XMAS\"}",
            "end_turn"));
    CoverScene cover = client().choose("겨울방", List.of("크리스마스", "고양이")).orElseThrow().cover();
    assertThat(cover.value()).isEqualTo("bedroom.night.snow.berry.0.cat.snow~xmas");
  }

  @Test
  void noneBackdropAndEventAreNotStored() throws IOException {
    start(
        200,
        response(
            "{\"base\":\"CAT\",\"tone\":\"ORIGINAL\",\"hat\":\"NONE\",\"charm\":\"NONE\","
                + "\"scene\":\"HANOK\",\"time\":\"DAY\",\"weather\":\"CLEAR\","
                + "\"palette\":\"WARM\",\"backdrop\":\"NONE\",\"event\":\"NONE\"}",
            "end_turn"));
    CoverScene plain = client().choose("한옥", List.of("전통")).orElseThrow().cover();
    assertThat(plain.backdrop()).isNull();
    assertThat(plain.event()).isNull();
    assertThat(plain.value()).isEqualTo("hanok.day.clear.warm.0.-.~");
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
