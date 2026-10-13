package com.blog.main.api;

import com.blog.board.service.SvgRasterizer;
import com.blog.main.service.ShareMetaService;
import com.blog.main.service.ShareMetaService.ShareMeta;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;

/**
 * 공유 미리보기가 붙는 화면: 메인, 블로그 {@code /blog/{주소}}, 글 {@code /blog/{주소}/posts/{번호}} (T161, T069, BRD-07,
 * D-66, D-70). 화면은 그대로 정적 HTML이고 JS가 주소를 읽어 API를 부른다. 서버는 {@code <title>}과 {@code <!--og-->} 자리에
 * Open Graph·트위터 카드 태그만 넣는다. 값은 모두 HTML 이스케이프한다(SEC-06).
 *
 * <p>og:url·og:image는 절대 주소여야 한다. {@code app.site.base-url}(운영 https://jj-blog.java21.net)이 있으면
 * 그것을, 없으면 요청 주소를 쓴다.
 */
@Controller
public class SharePageController {

  static final String PLACEHOLDER = "<!--og-->";
  private static final Pattern TITLE = Pattern.compile("<title>[^<]*</title>");

  private final ShareMetaService metaService;
  private final String baseUrl;
  private final Map<String, String> templates = new ConcurrentHashMap<>();

  public SharePageController(
      ShareMetaService metaService, @Value("${app.site.base-url:}") String baseUrl) {
    this.metaService = metaService;
    this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
  }

  @GetMapping({"/", "/index.html"})
  public ResponseEntity<String> home(HttpServletRequest request) {
    return page("index.html", metaService.home(), request);
  }

  @GetMapping("/blog/{slug:[a-z0-9-]+}")
  public ResponseEntity<String> blog(
      @PathVariable String slug,
      @RequestParam(required = false) String share,
      HttpServletRequest request) {
    return page("blog.html", metaService.blog(slug, share), request);
  }

  @GetMapping("/blog/{slug:[a-z0-9-]+}/posts/{postId:\\d{1,18}}")
  public ResponseEntity<String> post(
      @PathVariable String slug,
      @PathVariable Long postId,
      @RequestParam(required = false) String share,
      HttpServletRequest request) {
    return page("post.html", metaService.post(slug, postId, share), request);
  }

  private ResponseEntity<String> page(String file, ShareMeta meta, HttpServletRequest request) {
    String template = templates.computeIfAbsent(file, SharePageController::load);
    String origin =
        baseUrl.isEmpty()
            ? ServletUriComponentsBuilder.fromContextPath(request).build().toUriString()
            : baseUrl;
    String html =
        TITLE
            .matcher(template)
            .replaceFirst(Matcher.quoteReplacement("<title>" + esc(meta.pageTitle()) + "</title>"))
            .replace(PLACEHOLDER, tags(meta, origin));
    return ResponseEntity.ok()
        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
        .cacheControl(CacheControl.noCache())
        .body(html);
  }

  private String tags(ShareMeta meta, String origin) {
    String image = absolute(meta.image(), origin);
    StringBuilder sb = new StringBuilder();
    name(sb, "description", meta.description());
    property(sb, "og:type", meta.type());
    property(sb, "og:site_name", metaService.siteName());
    property(sb, "og:locale", "ko_KR");
    property(sb, "og:title", meta.title());
    property(sb, "og:description", meta.description());
    property(sb, "og:url", absolute(meta.url(), origin));
    property(sb, "og:image", image);
    if (meta.imageSized()) {
      property(sb, "og:image:width", String.valueOf(SvgRasterizer.WIDTH));
      property(sb, "og:image:height", String.valueOf(SvgRasterizer.HEIGHT));
    }
    property(sb, "og:image:alt", meta.title());
    name(sb, "twitter:card", "summary_large_image");
    name(sb, "twitter:title", meta.title());
    name(sb, "twitter:description", meta.description());
    name(sb, "twitter:image", image);
    return sb.toString().stripTrailing();
  }

  private static String absolute(String path, String origin) {
    return path.startsWith("/") ? origin + path : path;
  }

  private static void property(StringBuilder sb, String property, String content) {
    meta(sb, "property", property, content);
  }

  private static void name(StringBuilder sb, String name, String content) {
    meta(sb, "name", name, content);
  }

  private static void meta(StringBuilder sb, String attr, String key, String content) {
    sb.append("<meta ")
        .append(attr)
        .append("=\"")
        .append(key)
        .append("\" content=\"")
        .append(esc(content))
        .append("\" />\n    ");
  }

  private static String esc(String value) {
    return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8");
  }

  private static String load(String file) {
    try (InputStream in = new ClassPathResource("static/" + file).getInputStream()) {
      String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      if (!html.contains(PLACEHOLDER)) {
        throw new IllegalStateException(file + "에 " + PLACEHOLDER + " 자리가 없음");
      }
      return html;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
