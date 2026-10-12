package com.blog.main.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 릴리즈 노트 API와 화면을 비회원으로 확인한다 (T159, D-130). */
@IntegrationTest
class ReleaseNoteFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;

  @Test
  void listIsPublicAndNewestFirst() throws Exception {
    JsonNode list = body("/api/release-notes");
    assertThat(list.isArray()).isTrue();
    assertThat(list.size()).isGreaterThanOrEqualTo(2);

    // 파일에 있는 버전이 모두 나오고, 새 버전부터
    List<String> versions = new ArrayList<>();
    list.forEach(n -> versions.add(n.get("version").asString()));
    assertThat(versions).containsAll(List.of("v1.0.0", "v1.0.1"));
    assertThat(versions.getLast()).isEqualTo("v1.0.0");
    for (int i = 1; i < versions.size(); i++) {
      assertThat(patch(versions.get(i - 1))).isGreaterThan(patch(versions.get(i)));
    }

    JsonNode first = list.get(0);
    assertThat(first.get("title").asString()).isNotBlank();
    assertThat(LocalDate.parse(first.get("date").asString())).isNotNull();
    // 목록에는 바뀐 점을 싣지 않는다
    assertThat(first.has("changes")).isFalse();
  }

  @Test
  void newestEntryMatchesWhatTheReleaseWorkflowReads() throws Exception {
    // release.yml은 파일의 releases[0]을 태그로 쓴다. 파일 맨 위가 가장 새 버전이어야 한다
    JsonNode file =
        jsonMapper.readTree(new ClassPathResource("release-notes.json").getInputStream());
    String top = file.get("releases").get(0).get("version").asString();
    assertThat(body("/api/release-notes").get(0).get("version").asString()).isEqualTo(top);
  }

  @Test
  void detailShowsTitlesJoinedAndChanges() throws Exception {
    mockMvc
        .perform(get("/api/release-notes/v1.14.0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("v1.14.0"))
        .andExpect(jsonPath("$.date").value("2026-10-12"))
        .andExpect(jsonPath("$.title").value("목록형 화면, 어두운 모드, 픽셀 마스코트"))
        .andExpect(jsonPath("$.changes").isArray())
        .andExpect(jsonPath("$.changes[0]").isNotEmpty())
        // PR 번호는 관리용이라 내보내지 않는다
        .andExpect(jsonPath("$.prs").doesNotExist());

    // v를 빼고 불러도 같은 버전
    mockMvc
        .perform(get("/api/release-notes/1.0.0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("v1.0.0"))
        .andExpect(jsonPath("$.date").value("2026-10-07"));
  }

  @Test
  void unknownVersionIs404() throws Exception {
    mockMvc
        .perform(get("/api/release-notes/v9.9.9"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void pagesArePublic() throws Exception {
    mockMvc
        .perform(get("/release-notes.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/js/release-notes.js")));
    mockMvc.perform(get("/release-note.html")).andExpect(status().isOk());
    mockMvc.perform(get("/js/release-notes.js")).andExpect(status().isOk());
  }

  private JsonNode body(String url) throws Exception {
    String json =
        mockMvc
            .perform(get(url))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return jsonMapper.readTree(json);
  }

  private static int patch(String version) {
    String[] parts = version.substring(1).split("\\.");
    return Integer.parseInt(parts[0]) * 1_000_000
        + Integer.parseInt(parts[1]) * 1_000
        + Integer.parseInt(parts[2]);
  }
}
