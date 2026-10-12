package com.blog.main.repository;

import com.blog.main.domain.ReleaseNote;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 릴리즈 노트 파일 읽기 (T159, D-130). 저장소가 비공개라 서버가 GitHub Releases를 읽지 못해서, 노트를 저장소 안 파일로 두고 서버가 뜰 때 한 번
 * 읽는다. 파일이 잘못됐으면(버전 형식, 빈 제목, 같은 버전 두 번, 새 버전이 위에 있지 않음) 서버가 뜨지 않는다.
 *
 * <p>같은 파일을 {@code .github/workflows/release.yml}이 읽어 가장 새 버전으로 태그와 GitHub 릴리즈를 만든다.
 */
@Repository
public class ReleaseNoteRepository {

  /** 새 버전이 앞에 오도록 */
  static final Comparator<ReleaseNote> NEWEST_FIRST =
      Comparator.comparing(ReleaseNote::versionNumbers, Arrays::compare).reversed();

  /** 맨 위 {@code _rule} 같은 설명 칸은 읽지 않는다 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ReleaseNotesFile(List<ReleaseNote> releases) {}

  private final List<ReleaseNote> notes;

  public ReleaseNoteRepository(
      JsonMapper jsonMapper,
      @Value("${app.release-notes.location:classpath:release-notes.json}") Resource file) {
    this.notes = read(jsonMapper, file);
  }

  static List<ReleaseNote> read(JsonMapper jsonMapper, Resource file) {
    ReleaseNotesFile parsed;
    try (InputStream in = file.getInputStream()) {
      parsed = jsonMapper.readValue(in, ReleaseNotesFile.class);
    } catch (IOException e) {
      throw new UncheckedIOException("릴리즈 노트 파일을 읽지 못했어요: " + file, e);
    }
    if (parsed == null || parsed.releases() == null) {
      throw new IllegalStateException("릴리즈 노트 파일에 releases가 없어요: " + file);
    }
    List<ReleaseNote> releases = parsed.releases();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < releases.size(); i++) {
      ReleaseNote note = releases.get(i);
      if (!seen.add(note.version())) {
        throw new IllegalStateException("같은 버전이 두 번 있어요: " + note.version());
      }
      // release.yml은 맨 위 항목을 다음 태그로 쓴다. 파일 순서가 곧 새 버전 순서여야 한다
      if (i > 0 && NEWEST_FIRST.compare(releases.get(i - 1), note) >= 0) {
        throw new IllegalStateException(
            "새 버전이 위에 오게 적어요: " + releases.get(i - 1).version() + " 다음에 " + note.version());
      }
    }
    return List.copyOf(releases);
  }

  /** 새 버전부터 */
  public List<ReleaseNote> findAll() {
    return notes;
  }

  public Optional<ReleaseNote> findByVersion(String version) {
    return notes.stream().filter(n -> n.version().equals(version)).findFirst();
  }
}
