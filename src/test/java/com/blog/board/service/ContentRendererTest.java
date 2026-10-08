package com.blog.board.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blog.common.error.BusinessException;
import org.junit.jupiter.api.Test;

/** 본문 변환과 XSS 거르기 (T061, SEC-06, D-64, D-92). */
class ContentRendererTest {

  private static final String IMG = "/api/images/0f8fad5b-d9cb-469f-a165-70867728950e.png";

  ContentRenderer renderer = new ContentRenderer();

  @Test
  void dangerousHtmlIsRemoved() {
    String html =
        renderer.render(
            "# 제목\n\n<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n"
                + "[클릭](javascript:alert(1))\n\n<a href=\"https://example.com\" onclick=\"x()\">링크</a>");

    assertThat(html).contains("<h1>제목</h1>");
    assertThat(html).doesNotContain("<script").doesNotContain("onerror").doesNotContain("onclick");
    assertThat(html).doesNotContain("javascript:");
    assertThat(html).contains("rel=\"nofollow noopener noreferrer\"");
  }

  @Test
  void onlyOwnImagesStay() {
    String html = renderer.render("![a](" + IMG + ")\n\n![b](https://evil.example/x.png)");

    assertThat(html).contains("src=\"" + IMG + "\"");
    assertThat(html).doesNotContain("evil.example");
  }

  @Test
  void tablesAndStrikethroughRender() {
    String html = renderer.render("| a | b |\n|---|---|\n| 1 | 2 |\n\n~~지움~~");

    assertThat(html).contains("<table>").contains("<del>지움</del>");
  }

  @Test
  void lengthCountsEveryCharacterUpTo5000() {
    assertThat(ContentRenderer.validate("가".repeat(5000))).hasSize(5000);
    assertThatThrownBy(() -> ContentRenderer.validate("가".repeat(5001)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("5,000자");
    // 이모지 하나는 한 글자
    assertThat(ContentRenderer.validate("😀".repeat(5000))).isNotNull();
    assertThatThrownBy(() -> ContentRenderer.validate("  ")).isInstanceOf(BusinessException.class);
  }

  @Test
  void imageNamesInOrderWithoutDuplicates() {
    String other = "/api/images/1f8fad5b-d9cb-469f-a165-70867728950e.jpg";
    assertThat(ContentRenderer.imageNames("![](" + other + ") ![](" + IMG + ") ![](" + other + ")"))
        .containsExactly(other.substring(12), IMG.substring(12));
  }
}
