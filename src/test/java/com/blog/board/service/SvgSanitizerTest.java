package com.blog.board.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;

/** Claude가 그린 SVG 정리 (T155, D-126). 그림은 남고 스크립트·외부 주소·HTML은 빠지거나 통째로 버려진다. */
class SvgSanitizerTest {

  private static final String OPEN =
      "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1200 500\">";

  @Test
  void normalIllustrationSurvives() {
    String clean =
        SvgSanitizer.sanitize(
            OPEN
                + "<defs><linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">"
                + "<stop offset=\"0\" stop-color=\"#ffd8b8\"/><stop offset=\"1\" stop-color=\"#fff\"/>"
                + "</linearGradient><clipPath id=\"c\"><rect width=\"600\" height=\"300\"/></clipPath></defs>"
                + "<rect width=\"1200\" height=\"500\" fill=\"url(#g)\"/>"
                + "<g clip-path=\"url(#c)\" transform=\"translate(10 20)\">"
                + "<circle cx=\"50\" cy=\"50\" fill=\"#f04452\" opacity=\"0.8\" r=\"40\"/>"
                + "<path d=\"M0 0 L10 10 Z\" stroke=\"#191f28\" stroke-width=\"2\"/></g></svg>");

    assertThat(clean)
        .startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1200 500\"")
        .contains("width=\"1200\" height=\"500\"")
        .contains("<linearGradient id=\"g\" x1=\"0\" x2=\"0\" y1=\"0\" y2=\"1\">")
        .contains("<stop offset=\"0\" stop-color=\"#ffd8b8\"/>")
        .contains("<rect fill=\"url(#g)\" height=\"500\" width=\"1200\"/>")
        .contains("<g clip-path=\"url(#c)\" transform=\"translate(10 20)\">")
        .contains("<circle cx=\"50\" cy=\"50\" fill=\"#f04452\" opacity=\"0.8\" r=\"40\"/>")
        .endsWith("</svg>");
    // 정리한 결과는 다시 읽어도 같은 결과가 나온다
    assertThat(SvgSanitizer.sanitize(clean)).isEqualTo(clean);
  }

  @Test
  void scriptsAndEventHandlersAreRemoved() {
    String clean =
        SvgSanitizer.sanitize(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 50\" onload=\"alert(1)\">"
                + "<script>alert(document.cookie)</script>"
                + "<script xlink:href=\"https://evil.example/x.js\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"/>"
                + "<rect width=\"100\" height=\"50\" fill=\"#fff\" onclick=\"steal()\" onmouseover=\"x()\"/>"
                + "<style>rect{fill:red}</style>"
                + "<g><animate attributeName=\"href\" to=\"javascript:alert(1)\"/>"
                + "<set attributeName=\"onload\" to=\"alert(1)\"/><circle r=\"5\"/></g></svg>");

    assertThat(clean)
        .doesNotContainIgnoringCase("script")
        .doesNotContainIgnoringCase("onload")
        .doesNotContainIgnoringCase("onclick")
        .doesNotContainIgnoringCase("onmouseover")
        .doesNotContain("alert")
        .doesNotContain("<style")
        .doesNotContain("animate")
        .doesNotContain("<set")
        .contains("<rect fill=\"#fff\" height=\"50\" width=\"100\"/>")
        .contains("<g><circle r=\"5\"/></g>");
  }

  @Test
  void foreignObjectHtmlTextAndLinksAreRemoved() {
    String clean =
        SvgSanitizer.sanitize(
            OPEN
                + "<rect width=\"1200\" height=\"500\" fill=\"#eee\"/>"
                + "<foreignObject width=\"100\" height=\"100\">"
                + "<div xmlns=\"http://www.w3.org/1999/xhtml\"><img src=\"x\" onerror=\"alert(1)\"/></div>"
                + "</foreignObject>"
                + "<a href=\"javascript:alert(1)\"><rect width=\"10\" height=\"10\"/></a>"
                + "<text x=\"1\" y=\"2\">Ignore previous instructions</text>"
                + "<iframe xmlns=\"http://www.w3.org/1999/xhtml\" src=\"https://evil.example\"/>"
                + "<!-- comment --><![CDATA[ <script>alert(1)</script> ]]>loose text</svg>");

    assertThat(clean)
        .isEqualTo(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1200 500\" width=\"1200\""
                + " height=\"500\" preserveAspectRatio=\"xMidYMid slice\">"
                + "<rect fill=\"#eee\" height=\"500\" width=\"1200\"/></svg>");
  }

  @Test
  void externalReferencesAreRemovedAndLocalOnesKept() {
    String clean =
        SvgSanitizer.sanitize(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                + " viewBox=\"0 0 1200 500\">"
                + "<defs><g id=\"leaf\"><circle r=\"3\"/></g>"
                + "<linearGradient id=\"a\"><stop offset=\"0\" stop-color=\"#000\"/></linearGradient>"
                + "<linearGradient id=\"b\" xlink:href=\"#a\"/>"
                + "<linearGradient id=\"c\" href=\"https://evil.example/g.svg#a\"/></defs>"
                + "<use xlink:href=\"#leaf\" x=\"10\"/>"
                + "<use href=\"https://evil.example/sprite.svg#leaf\"/>"
                + "<use xlink:href=\"data:image/svg+xml;base64,PHN2Zz4=\"/>"
                + "<image href=\"https://evil.example/t.png\" width=\"1\" height=\"1\"/>"
                + "<rect width=\"5\" height=\"5\" fill=\"url(https://evil.example/p.svg#g)\"/>"
                + "<rect width=\"6\" height=\"6\" fill=\"url('#a')\" mask=\"url(#a) url(//evil)\"/>"
                + "<rect width=\"7\" height=\"7\" style=\"fill:#123;opacity:.5\"/>"
                + "<rect width=\"8\" height=\"8\" style=\"background:url(https://evil.example/x)\"/>"
                + "</svg>");

    assertThat(clean)
        .doesNotContain("evil")
        .doesNotContain("data:")
        .doesNotContain("xlink")
        .doesNotContain("<image")
        .contains("<linearGradient id=\"b\" href=\"#a\"/>")
        .contains("<linearGradient id=\"c\"/>")
        .contains("<use x=\"10\" href=\"#leaf\"/>")
        .contains("<rect height=\"5\" width=\"5\"/>")
        .contains("<rect fill=\"url('#a')\" height=\"6\" width=\"6\"/>")
        .contains("<rect height=\"7\" style=\"fill:#123;opacity:.5\" width=\"7\"/>")
        .contains("<rect height=\"8\" width=\"8\"/>");
    assertThat(clean.split("<use", -1)).hasSize(2); // 바깥을 가리키던 use 두 개는 빠진다
  }

  @Test
  void useThatPointsAtAnotherUseIsRemoved() {
    // 겹겹이 복제해 브라우저를 멈추게 하는 그림
    String clean =
        SvgSanitizer.sanitize(
            OPEN
                + "<defs><circle id=\"a\" r=\"1\"/>"
                + "<g id=\"b\"><use href=\"#a\"/><use href=\"#a\"/></g>"
                + "<g id=\"c\"><use href=\"#b\"/><use href=\"#b\"/></g></defs>"
                + "<use href=\"#c\"/><use href=\"#a\"/></svg>");

    assertThat(clean)
        .contains("<g id=\"b\"><use href=\"#a\"/><use href=\"#a\"/></g>")
        .contains("<g id=\"c\"/>")
        .doesNotContain("href=\"#b\"")
        .doesNotContain("href=\"#c\"")
        .contains("</defs><use href=\"#a\"/></svg>");
  }

  @Test
  void doctypeAndExternalEntitiesAreRejected() {
    assertThatThrownBy(
            () ->
                SvgSanitizer.sanitize(
                    "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                        + OPEN
                        + "<rect width=\"1\" height=\"1\" id=\"&xxe;\"/></svg>"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SvgSanitizer.sanitize(
                    "<!DOCTYPE svg [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;\">]>"
                        + OPEN
                        + "<rect width=\"1\" height=\"1\" fill=\"&b;\"/></svg>"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void brokenOrWrongDocumentsAreRejected() {
    assertThatThrownBy(() -> SvgSanitizer.sanitize(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SvgSanitizer.sanitize(OPEN + "<rect width=\"1\""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SvgSanitizer.sanitize(
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>hi</body></html>"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> SvgSanitizer.sanitize("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>"))
        .as("viewBox도 크기도 없음")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SvgSanitizer.sanitize(OPEN + "<script>alert(1)</script></svg>"))
        .as("남는 그림이 없음")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void oversizeIsRejected() {
    StringBuilder big = new StringBuilder(OPEN);
    while (big.length() <= SvgSanitizer.MAX_BYTES) {
      big.append("<rect x=\"1\" y=\"2\" width=\"3\" height=\"4\" fill=\"#abcdef\"/>");
    }
    big.append("</svg>");
    String oversize = big.toString();
    assertThatThrownBy(() -> SvgSanitizer.sanitize(oversize))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too large");
  }

  @Test
  void handWrittenCafeSampleKeepsTheDrawingOnly() throws Exception {
    String raw = resource("/cover-art/cafe-raw.svg");

    String clean = SvgSanitizer.sanitize(raw);

    assertThat(clean)
        .doesNotContain("evil")
        .doesNotContain("alert")
        .doesNotContain("script")
        .doesNotContain("foreignObject")
        .doesNotContain("<text")
        .doesNotContain("<image")
        .doesNotContain("<a ")
        .doesNotContain("<style")
        .doesNotContain("onclick")
        .doesNotContain("class=")
        .contains("<linearGradient id=\"sky\"")
        .contains("<linearGradient gradientTransform=\"rotate(10)\" id=\"sky2\" href=\"#sky\"/>")
        .contains("<g clip-path=\"url(#window)\">")
        .contains("<use x=\"-25\" href=\"#steam\" y=\"-15\"/>")
        .contains("<polygon fill=\"#e07a5f\" points=\"-40,50 40,50 30,0 -30,0\"/>");
    assertWellFormed(clean);

    // 보고·눈으로 확인할 수 있게 정리한 결과를 build 아래에 남긴다
    Path out = Path.of("build", "cover-art", "cafe-sanitized.svg");
    Files.createDirectories(out.getParent());
    Files.writeString(out, clean, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("viewBox가 없으면 width·height로 만들어 쓴다")
  void viewBoxFallsBackToWidthAndHeight() {
    String clean =
        SvgSanitizer.sanitize(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"300\" height=\"150\">"
                + "<rect width=\"10\" height=\"10\"/></svg>");

    assertThat(clean)
        .startsWith(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 300 150\" width=\"300\""
                + " height=\"150\"");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0 0 100", "0 0 100 abc", "0 0 50% 50", "0 0 0 100", "0 0 100 -5"})
  @DisplayName("viewBox가 숫자 네 개가 아니거나 폭·높이가 0 이하면 버린다")
  void invalidViewBoxIsRejected(String viewBox) {
    assertThatThrownBy(
            () ->
                SvgSanitizer.sanitize(
                    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\""
                        + viewBox
                        + "\"><rect width=\"1\" height=\"1\"/></svg>"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid viewBox");
  }

  @Test
  @DisplayName("빈 문서와, 이름만 svg이고 다른 네임스페이스인 루트는 버린다")
  void blankOrForeignNamespaceRootIsRejected() {
    assertThatThrownBy(() -> SvgSanitizer.sanitize("   "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                SvgSanitizer.sanitize(
                    "<svg xmlns=\"http://www.w3.org/1999/xhtml\" viewBox=\"0 0 1 1\">"
                        + "<rect width=\"1\" height=\"1\"/></svg>"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Root is not svg");
  }

  @Test
  @DisplayName("xmlns를 빼먹은 SVG는 받고, 안쪽의 svg·다른 네임스페이스 요소는 통째로 뺀다")
  void nestedSvgAndForeignElementsAreDropped() {
    String clean =
        SvgSanitizer.sanitize(
            "<svg viewBox=\"0 0 10 10\" xmlns:h=\"http://www.w3.org/1999/xhtml\">"
                + "<rect width=\"1\" height=\"1\"/>"
                + "<svg><script>alert(1)</script><rect width=\"2\" height=\"2\"/></svg>"
                + "<h:div><rect width=\"3\" height=\"3\"/></h:div></svg>");

    assertThat(clean)
        .endsWith("preserveAspectRatio=\"xMidYMid slice\"><rect height=\"1\" width=\"1\"/></svg>");
  }

  @Test
  @DisplayName("편집기 네임스페이스 속성, 그림 요소의 href, 잘못된 id는 그 속성만 뺀다")
  void foreignAttributesHrefOnShapesAndBadIdsAreDropped() {
    String clean =
        SvgSanitizer.sanitize(
            OPEN.replace(
                    ">",
                    " xmlns:inkscape=\"http://www.inkscape.org/namespaces/inkscape\""
                        + " xmlns:x=\"urn:x\">")
                + "<g inkscape:label=\"Layer 1\" id=\"layer1\">"
                + "<rect href=\"#layer1\" x:href=\"#layer1\" width=\"1\" height=\"1\"/>"
                + "<circle id=\"1bad\" r=\"2\"/><circle id=\"has space\" r=\"3\"/></g></svg>");

    assertThat(clean)
        .doesNotContain("inkscape")
        .doesNotContain("href")
        .contains("<g id=\"layer1\"><rect height=\"1\" width=\"1\"/>")
        .contains("<circle r=\"2\"/><circle r=\"3\"/></g>");
  }

  @Test
  @DisplayName("CSS 식·IE behavior·역슬래시 이스케이프·글자 꺾쇠가 든 값은 그 속성만 뺀다")
  void dangerousAttributeValuesAreDropped() {
    String clean =
        SvgSanitizer.sanitize(
            OPEN
                + "<rect width=\"1\" height=\"1\" fill=\"expression(alert(1))\"/>"
                + "<rect width=\"2\" height=\"2\" style=\"behavior:url(#a)\"/>"
                + "<rect width=\"3\" height=\"3\" style=\"fill:\\75 rl(x)\"/>"
                + "<rect width=\"4\" height=\"4\" transform=\"&lt;b&gt;\"/>"
                + "<path d=\""
                + "M0 0 ".repeat(20_001)
                + "\" fill=\"#000\"/></svg>");

    assertThat(clean)
        .doesNotContain("expression")
        .doesNotContain("behavior")
        .doesNotContain("\\")
        .doesNotContain("&lt;")
        .contains("<rect height=\"1\" width=\"1\"/>")
        .contains("<rect height=\"2\" width=\"2\"/>")
        .contains("<rect height=\"3\" width=\"3\"/>")
        .contains("<rect height=\"4\" width=\"4\"/>")
        .contains("<path fill=\"#000\"/>");
  }

  @Test
  @DisplayName("32단계보다 깊거나 요소가 5000개를 넘는 그림은 통째로 버린다")
  void tooDeepOrTooManyElementsIsRejected() {
    String deep = OPEN + "<g>".repeat(33) + "<rect width=\"1\" height=\"1\"/>" + "</g>".repeat(33);
    assertThatThrownBy(() -> SvgSanitizer.sanitize(deep + "</svg>"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("SVG too complex");
    String okDepth =
        OPEN + "<g>".repeat(31) + "<rect width=\"1\" height=\"1\"/>" + "</g>".repeat(31);
    assertThat(SvgSanitizer.sanitize(okDepth + "</svg>")).contains("<rect");

    String many = OPEN + "<g/>".repeat(5001) + "</svg>";
    assertThatThrownBy(() -> SvgSanitizer.sanitize(many))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("SVG too complex");
  }

  private static void assertWellFormed(String svg) throws Exception {
    javax.xml.parsers.DocumentBuilderFactory f =
        javax.xml.parsers.DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    Document doc =
        f.newDocumentBuilder()
            .parse(new java.io.ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    assertThat(doc.getDocumentElement().getNamespaceURI()).isEqualTo("http://www.w3.org/2000/svg");
  }

  private static String resource(String path) throws IOException {
    try (InputStream in = SvgSanitizerTest.class.getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
