package com.blog.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 화면 HTML의 JS·CSS 주소에 내용 해시를 붙인다 (T164). */
class AssetVersionsTest {

  private final AssetVersions versions = new AssetVersions();

  @Test
  void addsContentHashOnlyToExistingScriptsAndStyles() {
    String api = versions.version("/js/api.js").orElseThrow();
    String css = versions.version("/css/common.css").orElseThrow();

    String html =
        versions.rewrite(
            """
            <link rel="stylesheet" href="/css/common.css" />
            <script src="/js/api.js" defer></script>
            <script src="/js/none.js" defer></script>
            <script src="/js/api.js?v=old" defer></script>
            <a href="/blog/a">blog</a>
            """);

    assertThat(api).matches("[0-9a-f]{12}");
    assertThat(html)
        .contains("href=\"/css/common.css?v=" + css + "\"")
        .contains("src=\"/js/api.js?v=" + api + "\" defer")
        .contains("src=\"/js/none.js\" defer")
        .contains("src=\"/js/api.js?v=old\"")
        .contains("href=\"/blog/a\"");
  }

  @Test
  void refusesPathsOutsideStatic() {
    assertThat(versions.version("/js/../application.yml")).isEmpty();
    assertThat(versions.version("/js/missing.js")).isEmpty();
  }
}
