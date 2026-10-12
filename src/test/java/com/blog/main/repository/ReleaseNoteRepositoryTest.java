package com.blog.main.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blog.main.domain.ReleaseNote;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** 릴리즈 노트 파일 검사 (T159, D-130). 잘못 적으면 서버가 뜨지 않고 release.yml이 엉뚱한 태그를 만들지 않는다. */
class ReleaseNoteRepositoryTest {

  JsonMapper jsonMapper = JsonMapper.builder().build();

  @Test
  void realFileIsValidAndStartsAtV100() {
    List<ReleaseNote> notes =
        ReleaseNoteRepository.read(jsonMapper, new ClassPathResource("release-notes.json"));
    assertThat(notes.getLast().version()).isEqualTo("v1.0.0");
    assertThat(notes.getLast().prs()).containsExactly(1);
  }

  @Test
  void versionsMustBeNewestFirst() {
    String json =
        """
        {"releases": [
          {"version": "v1.0.0", "date": "2026-10-07", "titles": ["a"], "changes": ["a"]},
          {"version": "v1.1.0", "date": "2026-10-08", "titles": ["b"], "changes": ["b"]}
        ]}
        """;
    assertThatThrownBy(() -> ReleaseNoteRepository.read(jsonMapper, resource(json)))
        .hasMessageContaining("새 버전이 위에");
  }

  @Test
  void minorIsComparedAsNumberNotText() {
    String json =
        """
        {"releases": [
          {"version": "v1.10.0", "date": "2026-10-08", "titles": ["b"], "changes": ["b"]},
          {"version": "v1.9.0", "date": "2026-10-07", "titles": ["a"], "changes": ["a"]}
        ]}
        """;
    assertThat(ReleaseNoteRepository.read(jsonMapper, resource(json)))
        .extracting(ReleaseNote::version)
        .containsExactly("v1.10.0", "v1.9.0");
  }

  @Test
  void duplicateOrBadVersionIsRejected() {
    String duplicate =
        """
        {"releases": [
          {"version": "v1.0.0", "date": "2026-10-07", "titles": ["a"], "changes": ["a"]},
          {"version": "v1.0.0", "date": "2026-10-07", "titles": ["a"], "changes": ["a"]}
        ]}
        """;
    assertThatThrownBy(() -> ReleaseNoteRepository.read(jsonMapper, resource(duplicate)))
        .hasMessageContaining("두 번");

    String bad =
        """
        {"releases": [{"version": "1.0", "date": "2026-10-07", "titles": ["a"], "changes": ["a"]}]}
        """;
    assertThatThrownBy(() -> ReleaseNoteRepository.read(jsonMapper, resource(bad)))
        .hasStackTraceContaining("v1.0.0 꼴");
  }

  private static ByteArrayResource resource(String json) {
    return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
  }
}
