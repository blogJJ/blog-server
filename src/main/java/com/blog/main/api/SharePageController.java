package com.blog.main.api;

import com.blog.board.service.SvgRasterizer;
import com.blog.common.config.StaticResourceConfig;
import com.blog.common.web.AssetVersions;
import com.blog.common.web.SiteUrl;
import com.blog.main.service.ShareMetaService;
import com.blog.main.service.ShareMetaService.ShareMeta;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;

/**
 * 공유 미리보기가 붙는 화면: 메인, 블로그 {@code /blog/{주소}}, 글 {@code /blog/{주소}/posts/{번호}} (T161, T069, BRD-07,
 * D-66, D-70). 화면은 그대로 정적 HTML이고 JS가 주소를 읽어 API를 부른다. 서버는 {@code <title>}과 {@code <!--og-->} 자리에
 * Open Graph·트위터 카드 태그만 넣는다. 값은 모두 HTML 이스케이프한다(SEC-06).
 *
 * <p>og:url·og:image는 절대 주소여야 해서 앞부분은 {@link SiteUrl}(운영 https://jj-blog.java21.net, T160)을 쓴다.
 *
 * <p>빨리 뜨게 (T164):
 *
 * <ul>
 *   <li>JS·CSS 주소에 내용 해시({@code ?v=})를 붙여 브라우저가 화면을 옮길 때마다 다시 받지 않게 한다({@link AssetVersions}).
 *   <li>화면 JS가 곧 부를 API를 {@code <link rel="preload" as="fetch">}로 HTML과 함께 받기 시작하게 한다. 예전에는 JS를 다
 *       받고 실행한 뒤 블로그 정보를 받고, 그 번호로 다시 글 목록을 받는 차례 요청이었다. 주소는 화면 JS가 만드는 주소와 글자 하나까지 같아야 브라우저가 미리 받은
 *       응답을 쓴다. 공유 링크 값(share)이 주소에 그대로 쓸 수 없는 글자면 미리 받지 않는다.
 *   <li>블로그·글 링크에 마우스를 올리면 그 화면 HTML을 미리 받게 하는 규칙({@code /speculation-rules.json})을 헤더로 알린다. 화면
 *       HTML만 받고 API는 부르지 않아 조회수 등에 영향이 없다.
 * </ul>
 */
@Controller
public class SharePageController {

  static final String PLACEHOLDER = "<!--og-->";
  private static final Pattern TITLE = Pattern.compile("<title>[^<]*</title>");

  /** encodeURIComponent가 바꾸지 않는 글자만. 이런 share 값이면 화면 JS와 같은 주소를 만들 수 있다. */
  private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9_.~-]{1,200}");

  private static final Pattern PAGE_NUMBER = Pattern.compile("[1-9]\\d{0,8}");

  private static final String LATEST = "latest";
  private static final String POPULAR = "popular";

  /** 블로그·글 링크(블로그 관리 화면 제외)와 메인 링크에 마우스를 올리거나 누르기 시작하면 그 화면 HTML을 미리 받는다. */
  static final String SPECULATION_RULES_JSON =
      """
      {"prefetch":[{"where":{"and":[{"or":[{"href_matches":"/"},{"href_matches":"/blog/*"}]},\
      {"not":{"href_matches":"/blog/*/admin"}}]},"eagerness":"moderate"}]}
      """;

  private final ShareMetaService metaService;
  private final SiteUrl siteUrl;
  private final AssetVersions assetVersions;
  private final Map<String, String> templates = new ConcurrentHashMap<>();

  public SharePageController(
      ShareMetaService metaService, SiteUrl siteUrl, AssetVersions assetVersions) {
    this.metaService = metaService;
    this.siteUrl = siteUrl;
    this.assetVersions = assetVersions;
  }

  @GetMapping({"/", "/index.html"})
  public ResponseEntity<String> home(HttpServletRequest request) {
    List<String> preload = new ArrayList<>();
    String feed = feedUrl(request);
    if (feed != null) {
      preload.add(feed);
    }
    return page("index.html", metaService.home(), preload, request);
  }

  /** 화면 HTML 미리 받기 규칙 (Speculation Rules). 화면 HTML의 헤더가 이 주소를 가리킨다. */
  @GetMapping("/speculation-rules.json")
  public ResponseEntity<String> speculationRules() {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/speculationrules+json"))
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
        .body(SPECULATION_RULES_JSON);
  }

  @GetMapping("/blog/{slug:[a-z0-9-]+}")
  public ResponseEntity<String> blog(
      @PathVariable String slug,
      @RequestParam(required = false) String share,
      HttpServletRequest request) {
    ShareMeta meta = metaService.blog(slug, share);
    // blog.js, post-list.js가 부르는 주소와 같게
    String key = request.getParameter("share");
    List<String> preload = new ArrayList<>();
    if (shareUsable(key)) {
      preload.add("/api/blogs/by-slug/" + slug + query("?", key));
      if (meta.blogId() != null) {
        preload.add("/api/blogs/" + meta.blogId() + "/posts?page=1&size=10" + query("&", key));
        preload.add("/api/blogs/" + meta.blogId() + "/categories" + query("?", key));
      }
    }
    return page("blog.html", meta, preload, request);
  }

  @GetMapping("/blog/{slug:[a-z0-9-]+}/posts/{postId:\\d{1,18}}")
  public ResponseEntity<String> post(
      @PathVariable String slug,
      @PathVariable Long postId,
      @RequestParam(required = false) String share,
      HttpServletRequest request) {
    ShareMeta meta = metaService.post(slug, postId, share);
    // post-view.js는 주소의 번호 글자를 그대로 쓰고, comments.js는 API가 준 번호를 쓴다. 0으로 시작하는 주소 등은 미리 받지 않는다
    String key = request.getParameter("share");
    List<String> preload = new ArrayList<>();
    if (shareUsable(key) && request.getRequestURI().endsWith("/posts/" + postId)) {
      preload.add("/api/posts/" + postId + query("?", key));
      if (meta.blogId() != null) {
        preload.add("/api/posts/" + postId + "/comments?page=1" + query("&", key));
      }
    }
    return page("post.html", meta, preload, request);
  }

  private ResponseEntity<String> page(
      String file, ShareMeta meta, List<String> preload, HttpServletRequest request) {
    String template = templates.computeIfAbsent(file, this::load);
    String origin = siteUrl.origin(request);
    String html =
        TITLE
            .matcher(template)
            .replaceFirst(Matcher.quoteReplacement("<title>" + esc(meta.pageTitle()) + "</title>"))
            .replace(PLACEHOLDER, preloads(preload) + tags(meta, origin));
    return ResponseEntity.ok()
        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
        .cacheControl(CacheControl.noCache())
        .header(
            StaticResourceConfig.SPECULATION_RULES_HEADER, StaticResourceConfig.SPECULATION_RULES)
        .body(html);
  }

  /**
   * 화면 JS가 곧 부를 API. 로그인 확인(layout.js의 /api/me)은 모든 화면이 부른다. crossorigin은 fetch()의 기본 요청 방식(같은 사이트면
   * 쿠키 포함)과 맞춘 값이다.
   */
  private static String preloads(List<String> paths) {
    StringBuilder sb = new StringBuilder();
    List<String> all = new ArrayList<>(paths);
    all.add("/api/me");
    for (String path : all) {
      sb.append("<link rel=\"preload\" as=\"fetch\" crossorigin=\"anonymous\" href=\"")
          .append(esc(path))
          .append("\" />\n    ");
    }
    return sb.toString();
  }

  /** 메인 화면 feed.js가 처음 부르는 주소. 로그인해야 보이는 탭, 검색 주소(?q=), 알 수 없는 페이지 값이면 null. */
  private static String feedUrl(HttpServletRequest request) {
    if (request.getParameter("q") != null) {
      return null;
    }
    String tab = request.getParameter("tab");
    if (tab == null || !List.of(LATEST, POPULAR, "blogs").contains(tab)) {
      if (tab != null && List.of("following", "subscribed").contains(tab)) {
        return null;
      }
      tab = LATEST;
    }
    String page = request.getParameter("page");
    if (page == null) {
      page = "1";
    } else if (!PAGE_NUMBER.matcher(page).matches()) {
      return null;
    }
    if (tab.equals("blogs")) {
      String sort = POPULAR.equals(request.getParameter("sort")) ? POPULAR : LATEST;
      return "/api/blogs?sort=" + sort + "&page=" + page;
    }
    return "/api/feed?tab=" + tab + "&page=" + page;
  }

  /** 화면 JS는 share가 비어 있으면 붙이지 않는다. 주소에 그대로 쓸 수 없는 글자가 있으면 미리 받지 않는다. */
  private static boolean shareUsable(String key) {
    return key == null || key.isEmpty() || URL_SAFE.matcher(key).matches();
  }

  private static String query(String sep, String key) {
    return key == null || key.isEmpty() ? "" : sep + "share=" + key;
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

  private String load(String file) {
    try (InputStream in = new ClassPathResource("static/" + file).getInputStream()) {
      String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      if (!html.contains(PLACEHOLDER)) {
        throw new IllegalStateException(file + "에 " + PLACEHOLDER + " 자리가 없음");
      }
      return assetVersions.rewrite(html);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
