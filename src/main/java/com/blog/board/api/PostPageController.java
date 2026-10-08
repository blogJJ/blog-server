package com.blog.board.api;

import com.blog.board.service.PostQueryService;
import com.blog.board.service.PostQueryService.OgInfo;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 글 주소 {@code /blog/{주소}/posts/{번호}}. 화면은 정적 post.html이고, 공유 미리보기용 og 태그만 서버가 채운다 (T069, BRD-07,
 * D-66). 미리보기 이미지는 글의 첫 이미지, 없으면 블로그 대표 이미지. 비회원이 볼 수 없는 글은 og 태그를 채우지 않아 제목이 새지 않는다.
 */
@Controller
public class PostPageController {

  static final String PLACEHOLDER = "<!--og-->";

  private final PostQueryService queryService;
  private final String template;

  public PostPageController(PostQueryService queryService) {
    this.queryService = queryService;
    try (InputStream in = new ClassPathResource("static/post.html").getInputStream()) {
      this.template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @GetMapping("/blog/{slug:[a-z0-9-]+}/posts/{postId:\\d+}")
  public ResponseEntity<String> page(
      @PathVariable String slug,
      @PathVariable Long postId,
      @RequestParam(required = false) String share,
      HttpServletRequest request) {
    OgInfo og = queryService.ogInfo(postId, share);
    String html = template.replace(PLACEHOLDER, og == null ? "" : ogTags(og, request));
    return ResponseEntity.ok()
        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
        .cacheControl(CacheControl.noCache())
        .body(html);
  }

  private static String ogTags(OgInfo og, HttpServletRequest request) {
    String origin =
        UriComponentsBuilder.fromUriString(request.getRequestURL().toString())
            .replacePath(null)
            .replaceQuery(null)
            .build()
            .toUriString();
    StringBuilder sb = new StringBuilder();
    meta(sb, "og:type", "article");
    meta(sb, "og:site_name", og.blogName());
    meta(sb, "og:title", og.title());
    meta(sb, "og:description", og.description());
    String query = request.getQueryString();
    meta(sb, "og:url", request.getRequestURL() + (query == null ? "" : "?" + query));
    if (og.image() != null) {
      meta(sb, "og:image", origin + og.image());
    }
    return sb.toString();
  }

  private static void meta(StringBuilder sb, String property, String content) {
    sb.append("<meta property=\"")
        .append(property)
        .append("\" content=\"")
        .append(HtmlUtils.htmlEscape(content == null ? "" : content, "UTF-8"))
        .append("\" />\n    ");
  }
}
