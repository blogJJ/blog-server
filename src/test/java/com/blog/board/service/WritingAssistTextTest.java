package com.blog.board.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.board.service.ClaudeWritingClient.SpellingFix;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 글쓰기 도우미의 글 다듬기, 단어 세기 태그, 맞춤법 후보 위치 찾기 (T167). */
class WritingAssistTextTest {

  @Test
  void plainStripsMarkdownHtmlCodeAndUrls() {
    String md =
        """
        # 제목
        **굵게** [링크 글](https://a.com) ![그림](/api/images/1)
        ```java
        class A {}
        ```
        <b>태그</b> `code` https://b.com/x &amp; 끝\
        """;
    assertThat(AssistText.plain(md)).isEqualTo("제목 굵게 링크 글 태그 끝");
    assertThat(AssistText.plain(null)).isEmpty();
  }

  @Test
  void headCutsByCodePoint() {
    assertThat(AssistText.head("가나다", 2)).isEqualTo("가나");
    assertThat(AssistText.head("a😀b", 2)).isEqualTo("a😀");
    assertThat(AssistText.head("짧음", 10)).isEqualTo("짧음");
  }

  @Test
  void keywordSuggesterPrefersKnownTagsThenFrequentNouns() {
    String body = "고양이가 귀여워요. 고양이 사진을 찍었어요. 사진 보정도 했어요. 정말 정말 좋아요.";
    List<String> tags =
        KeywordTagSuggester.suggest("고양이 사진", body, List.of("사진", "여행"), List.of(), 5);
    // '고양이'를 '고양'으로 자르지 않는다
    assertThat(tags).startsWith("사진").contains("고양이").doesNotContain("여행", "정말", "좋아요", "고양");

    assertThat(KeywordTagSuggester.suggest("고양이 사진", body, List.of("사진"), List.of("사진"), 5))
        .doesNotContain("사진");
    assertThat(KeywordTagSuggester.suggest("", "", List.of(), List.of(), 5)).isEmpty();
  }

  @Test
  void locateFindsEachFixInOrderAndDropsBadOnes() {
    String text = "되요 되요 그리고 안되요";
    List<WritingAssistService.Fix> fixes =
        WritingAssistService.locate(
            text,
            List.of(
                new SpellingFix("되요", "돼요", "이유"),
                new SpellingFix("되요", "돼요", "이유"),
                new SpellingFix("없는말", "고침", ""),
                new SpellingFix("그리고", "그리고", "같음"),
                new SpellingFix("안되요", "안 돼요", null)));
    assertThat(fixes).extracting(WritingAssistService.Fix::start).containsExactly(0, 3, 10);
    assertThat(fixes.get(2).end()).isEqualTo(13);
    assertThat(fixes.get(2).reason()).isEmpty();
    // 같은 곳을 두 번 고르지 않는다
    assertThat(
            WritingAssistService.locate(
                "되요", List.of(new SpellingFix("되요", "돼요", ""), new SpellingFix("되요", "돼요", ""))))
        .hasSize(1);
  }
}
