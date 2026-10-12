package com.blog.main.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 버전 하나의 릴리즈 노트 (T159, D-130). 원본은 {@code src/main/resources/release-notes.json}이고 DB에 두지 않는다.
 *
 * @param version {@code v1.0.3} 꼴
 * @param date 그 버전을 이룬 PR이 main에 머지된 날
 * @param titles 짧은 제목들. 목록에는 쉼표로 이어 한 줄로 보여 준다
 * @param changes 사용자에게 보여 줄 바뀐 점, 한 줄에 하나
 * @param prs 그 버전에 든 PR 번호(관리용, 화면에는 보이지 않는다). 아직 머지 전이면 비어 있다
 */
public record ReleaseNote(
    String version, LocalDate date, List<String> titles, List<String> changes, List<Integer> prs) {

  public static final Pattern VERSION = Pattern.compile("v(\\d+)\\.(\\d+)\\.(\\d+)");

  public ReleaseNote {
    if (version == null || !VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("버전은 v1.0.0 꼴이어야 해요: " + version);
    }
    if (date == null) {
      throw new IllegalArgumentException(version + ": date가 없어요");
    }
    if (titles == null || titles.isEmpty() || titles.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException(version + ": titles가 비어 있어요");
    }
    if (changes == null || changes.isEmpty() || changes.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException(version + ": changes가 비어 있어요");
    }
    titles = List.copyOf(titles);
    changes = List.copyOf(changes);
    prs = prs == null ? List.of() : List.copyOf(prs);
  }

  /** 목록에 보여 줄 한 줄 제목: 짧은 제목들을 쉼표로 잇는다 */
  public String title() {
    return String.join(", ", titles);
  }

  /** 버전 비교용 숫자 [major, minor, patch] */
  public int[] versionNumbers() {
    Matcher m = VERSION.matcher(version);
    m.matches();
    return new int[] {
      Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))
    };
  }
}
