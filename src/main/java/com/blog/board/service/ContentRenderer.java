package com.blog.board.service;

import com.blog.board.domain.Post;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * 글 본문 처리 (T061, SEC-06, D-64, D-92).
 *
 * <ul>
 *   <li>본문은 마크다운 원문으로 저장하고, 보여 줄 때 HTML로 바꾼 뒤 jsoup Safelist로 위험한 태그·속성(script, on*, javascript:
 *       등)을 지운다. 화면은 이 결과만 innerHTML로 넣는다.
 *   <li>최대 5,000자. 공백과 마크다운 기호도 센다. 이모지도 한 글자로 센다.
 *   <li>이미지는 우리 서버 주소({@code /api/images/...})만 남긴다. CSP도 img-src 'self'로 한 번 더 막는다.
 * </ul>
 */
@Component
public class ContentRenderer {

  /** 본문에 넣은 우리 서버 이미지 주소 */
  public static final Pattern IMAGE_URL =
      Pattern.compile(
          "/api/images/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,5})");

  private static final Safelist SAFELIST =
      Safelist.relaxed()
          .addTags("del", "s", "hr")
          .addAttributes("code", "class")
          .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer")
          .addEnforcedAttribute("a", "target", "_blank")
          .preserveRelativeLinks(true);

  private static final String BASE_URI = "https://blog.invalid";

  private final Parser parser;
  private final HtmlRenderer renderer;

  public ContentRenderer() {
    List<Extension> extensions = List.of(TablesExtension.create(), StrikethroughExtension.create());
    this.parser = Parser.builder().extensions(extensions).build();
    this.renderer = HtmlRenderer.builder().extensions(extensions).build();
  }

  /** 길이 확인. 비었거나 5,000자를 넘으면 INVALID_INPUT. */
  public static String validate(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "본문을 입력해 주세요.");
    }
    int length = markdown.codePointCount(0, markdown.length());
    if (length > Post.CONTENT_MAX) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "본문은 5,000자까지 쓸 수 있어요. (지금 " + length + "자)");
    }
    return markdown;
  }

  /** 마크다운을 걸러낸 HTML로 바꾼다. */
  public String render(String markdown) {
    String html = renderer.render(parser.parse(markdown == null ? "" : markdown));
    Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
    String cleaned = Jsoup.clean(html, BASE_URI, SAFELIST, settings);
    // 우리 서버가 아닌 이미지는 지운다
    Document doc = Jsoup.parseBodyFragment(cleaned);
    doc.outputSettings(settings);
    doc.select("img")
        .forEach(
            img -> {
              if (!IMAGE_URL.matcher(img.attr("src")).matches()) {
                img.remove();
              } else {
                img.attr("loading", "lazy");
              }
            });
    return doc.body().html();
  }

  /** 첫 문단을 글자만 뽑는다 (공유 미리보기 og:description). */
  public String excerpt(String markdown, int maxChars) {
    String text =
        Jsoup.parse(renderer.render(parser.parse(markdown == null ? "" : markdown))).text();
    if (text.codePointCount(0, text.length()) <= maxChars) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, maxChars)) + "…";
  }

  /** 본문에 들어 있는 우리 서버 이미지의 저장 이름을 나온 순서대로. */
  public static List<String> imageNames(String markdown) {
    Set<String> names = new LinkedHashSet<>();
    Matcher m = IMAGE_URL.matcher(markdown == null ? "" : markdown);
    while (m.find()) {
      names.add(m.group(1));
    }
    return new ArrayList<>(names);
  }
}
