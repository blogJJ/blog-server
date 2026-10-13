package com.blog.main.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
import com.blog.board.service.SvgSanitizer;
import com.blog.board.service.TestImages;
import com.blog.common.security.AuthUser;
import com.blog.common.storage.FileStorage;
import com.blog.support.IntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공유 미리보기 태그를 미리보기 서버처럼 로그인 없이 HTML만 받아 확인한다 (T161, BRD-07). 볼 수 없는 블로그·글은 사이트 기본값만 나오고 제목·소개가 새지
 * 않아야 한다.
 */
@IntegrationTest
class SharePreviewFlowTest {

  private static final String ORIGIN = "http://localhost";
  private static final String CRAWLER =
      "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;
  @Autowired FileStorage storage;

  @Test
  void homeHasSiteCard() throws Exception {
    String html = page("/");
    assertThat(html)
        .contains("<title>블로그</title>")
        .contains(prop("og:site_name", "블로그"))
        .contains(prop("og:type", "website"))
        .contains(prop("og:url", ORIGIN + "/"))
        .contains(prop("og:image", ORIGIN + "/img/og-default.png"))
        .contains(prop("og:image:width", "1200"))
        .contains(name("twitter:card", "summary_large_image"))
        .doesNotContain("<!--og-->");
  }

  @Test
  void publicBlogShowsNameDescriptionAndDefaultImage() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC", "고양이 일기", "  매일   고양이와 지낸 이야기  ");
    String slug = slugOf(blogId);

    String html = page("/blog/" + slug);
    assertThat(html)
        .contains("<title>고양이 일기 - 블로그</title>")
        .contains(prop("og:title", "고양이 일기"))
        .contains(prop("og:description", "매일 고양이와 지낸 이야기"))
        .contains(name("description", "매일 고양이와 지낸 이야기"))
        .contains(prop("og:url", ORIGIN + "/blog/" + slug))
        .contains(prop("og:image", ORIGIN + "/img/og-default.png"))
        .contains(name("twitter:image", ORIGIN + "/img/og-default.png"))
        .contains(name("twitter:card", "summary_large_image"));

    // 기본 그림은 실제로 1200x630 PNG이고 캐시된다
    MockHttpServletResponse image =
        mockMvc
            .perform(get("/img/og-default.png"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(image.getContentType()).isEqualTo("image/png");
    assertThat(image.getHeader("Cache-Control")).contains("max-age=604800");
    BufferedImage png = ImageIO.read(new ByteArrayInputStream(image.getContentAsByteArray()));
    assertThat(png.getWidth()).isEqualTo(1200);
    assertThat(png.getHeight()).isEqualTo(630);
  }

  @Test
  void privateLinkOnlyAndClosedBlogsShowOnlySiteCard() throws Exception {
    User owner = newUser();
    long privateBlog = createBlog(owner, "PRIVATE", "비밀 정원", "아무도 몰라야 하는 소개");
    long linkOnly = createBlog(owner, "LINK_ONLY", "링크 정원", "링크로만 보는 소개");
    long closed = createBlog(owner, "PUBLIC", "닫힌 정원", "닫힌 블로그 소개");
    jdbc.update("update blogs set status = 'CLOSED', closed_at = now() where id = ?", closed);
    long hidden = createBlog(owner, "PUBLIC", "숨긴 정원", "숨긴 블로그 소개");
    jdbc.update("update blogs set is_hidden = true where id = ?", hidden);

    for (long id : List.of(privateBlog, linkOnly, closed, hidden)) {
      String slug = slugOf(id);
      String html = page("/blog/" + slug);
      assertThat(html)
          .doesNotContain(" 정원")
          .doesNotContain("블로그 소개")
          .doesNotContain("아무도 몰라야")
          .doesNotContain("링크로만 보는")
          .contains("<title>블로그</title>")
          .contains(prop("og:title", "블로그"))
          .contains(prop("og:url", ORIGIN + "/blog/" + slug))
          .contains(prop("og:image", ORIGIN + "/img/og-default.png"));
    }
    // 틀린 공유 값도 기본값
    assertThat(page("/blog/" + slugOf(linkOnly) + "?share=wrong")).doesNotContain("링크 정원");

    // 일부 공개 블로그를 공유 링크로 열면 받은 사람은 볼 수 있으니 채운다. og:url에도 링크 값을 둔다
    String key =
        jdbc.queryForObject("select share_key from blogs where id = ?", String.class, linkOnly);
    String shared = page("/blog/" + slugOf(linkOnly) + "?share=" + key);
    assertThat(shared)
        .contains(prop("og:title", "링크 정원"))
        .contains(prop("og:url", ORIGIN + "/blog/" + slugOf(linkOnly) + "?share=" + key));

    // 비공개 블로그의 글도 마찬가지
    long secret = writePost(owner, privateBlog, "비밀 제목", "비밀 본문");
    String post = page("/blog/" + slugOf(privateBlog) + "/posts/" + secret);
    assertThat(post)
        .doesNotContain("비밀 제목")
        .doesNotContain("비밀 본문")
        .contains(prop("og:title", "블로그"));
  }

  /**
   * 화면 JS가 곧 부를 API를 HTML과 함께 받기 시작한다 (T164). 주소는
   * blog.js·post-list.js·post-view.js·comments.js·feed.js가 만드는 주소와 같아야 브라우저가 미리 받은 응답을 쓴다. 비회원이 볼 수
   * 없는 블로그는 번호를 알리지 않는다.
   */
  @Test
  void pagesPreloadTheApisTheirScriptsCall() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC", "미리 받기", "소개");
    String slug = slugOf(blogId);
    long postId = writePost(owner, blogId, "제목", "본문");

    assertThat(page("/blog/" + slug))
        .contains(preload("/api/blogs/by-slug/" + slug))
        .contains(preload("/api/blogs/" + blogId + "/posts?page=1&amp;size=10"))
        .contains(preload("/api/blogs/" + blogId + "/categories"))
        .contains(preload("/api/me"))
        // JS·CSS 주소에는 내용 해시를 붙인다
        .containsPattern("<script src=\"/js/blog\\.js\\?v=[0-9a-f]{12}\" defer>")
        .containsPattern("href=\"/css/common\\.css\\?v=[0-9a-f]{12}\"");
    assertThat(page("/blog/" + slug + "/posts/" + postId))
        .contains(preload("/api/posts/" + postId))
        .contains(preload("/api/posts/" + postId + "/comments?page=1"));
    // 0으로 시작하는 번호는 post-view.js가 주소 글자를 그대로 써서 주소가 달라지므로 미리 받지 않는다
    assertThat(page("/blog/" + slug + "/posts/0" + postId)).doesNotContain(preload("/api/posts/"));

    // 공유 링크 값은 JS(encodeURIComponent)와 같은 주소를 만들 수 있을 때만
    long linkOnly = createBlog(owner, "LINK_ONLY", "링크 미리 받기", "소개");
    String key =
        jdbc.queryForObject("select share_key from blogs where id = ?", String.class, linkOnly);
    assertThat(page("/blog/" + slugOf(linkOnly) + "?share=" + key))
        .contains(preload("/api/blogs/by-slug/" + slugOf(linkOnly) + "?share=" + key))
        .contains(preload("/api/blogs/" + linkOnly + "/posts?page=1&amp;size=10&amp;share=" + key))
        .contains(preload("/api/blogs/" + linkOnly + "/categories?share=" + key));
    assertThat(page("/blog/" + slugOf(linkOnly) + "?share=a%20b"))
        .doesNotContain(preload("/api/blogs/by-slug/"))
        .contains(preload("/api/me"));

    // 비회원이 볼 수 없는 블로그는 블로그 정보만(블로그 번호를 알리지 않는다)
    long privateBlog = createBlog(owner, "PRIVATE", "비밀 미리 받기", "소개");
    assertThat(page("/blog/" + slugOf(privateBlog)))
        .contains(preload("/api/blogs/by-slug/" + slugOf(privateBlog)))
        .doesNotContain(preload("/api/blogs/" + privateBlog + "/"));

    // 메인: feed.js의 첫 요청. 로그인해야 보이는 탭은 미리 받지 않는다
    assertThat(page("/")).contains(preload("/api/feed?tab=latest&amp;page=1"));
    assertThat(page("/?tab=blogs&sort=popular&page=2"))
        .contains(preload("/api/blogs?sort=popular&amp;page=2"));
    assertThat(page("/?tab=following")).doesNotContain(preload("/api/feed"));
  }

  @Test
  void everyValueIsEscaped() throws Exception {
    User owner = newUser();
    String evil = "\"><script>x</script>";
    long blogId = createBlog(owner, "PUBLIC", "a\"<b>'&", evil);
    long postId = writePost(owner, blogId, "제목\"><img src=x onerror=1>", "<b>굵게</b> \"따옴표\" & 'x'");

    String blog = page("/blog/" + slugOf(blogId));
    assertThat(blog)
        .doesNotContain("<script>x")
        .contains(prop("og:title", "a&quot;&lt;b&gt;&#39;&amp;"))
        .contains("<title>a&quot;&lt;b&gt;&#39;&amp; - 블로그</title>")
        .contains(prop("og:description", "&quot;&gt;&lt;script&gt;x&lt;/script&gt;"));

    String post = page("/blog/" + slugOf(blogId) + "/posts/" + postId);
    assertThat(post)
        .doesNotContain("<img src=x")
        .contains(prop("og:title", "제목&quot;&gt;&lt;img src=x onerror=1&gt;"))
        .contains(prop("og:description", "굵게 &quot;따옴표&quot; &amp; &#39;x&#39;"));

    // 공유 값에 넣은 따옴표도 og:url을 깨지 못한다
    assertThat(page("/blog/" + slugOf(blogId) + "?share=%22%3E%3Cscript%3E"))
        .doesNotContain("\"><script>");
  }

  @Test
  void postShowsTitleExcerptAndFirstImage() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC", "여행 노트", "여행 블로그");
    String slug = slugOf(blogId);
    String imageUrl = upload(owner, TestImages.png());
    long postId =
        writePost(
            owner,
            blogId,
            "제주 3일",
            "# 첫날\n\n**바다**를 보고 [오름](https://example.com)에 올랐다.\n\n![사진]("
                + imageUrl
                + ")\n\n"
                + "가".repeat(200));

    String html = page("/blog/" + slug + "/posts/" + postId);
    // 마크다운·HTML을 걷어낸 앞 100자
    String description = metaContent(html, "og:description");
    assertThat(description).startsWith("첫날 바다를 보고 오름에 올랐다. 가가").endsWith("가…");
    assertThat(description.codePointCount(0, description.length())).isEqualTo(101);
    assertThat(html)
        .contains("<title>제주 3일 - 여행 노트</title>")
        .contains(prop("og:type", "article"))
        .contains(prop("og:title", "제주 3일"))
        .contains(prop("og:description", description))
        .contains(prop("og:url", ORIGIN + "/blog/" + slug + "/posts/" + postId))
        .contains(prop("og:image", ORIGIN + imageUrl))
        .doesNotContain("og:image:width");

    // 이미지가 없고 본문이 비면 블로그 소개와 기본 그림
    long plain = writePost(owner, blogId, "그림만", "![](https://example.com/a.png)");
    assertThat(page("/blog/" + slug + "/posts/" + plain))
        .contains(prop("og:description", "여행 블로그"))
        .contains(prop("og:image", ORIGIN + "/img/og-default.png"));

    // 다른 블로그 주소로 열거나 삭제한 글은 기본값
    long other = createBlog(owner, "PUBLIC", "다른 블로그", null);
    assertThat(page("/blog/" + slugOf(other) + "/posts/" + postId)).doesNotContain("제주");
    call(owner, delete("/api/posts/" + postId)).andExpect(status().isNoContent());
    assertThat(page("/blog/" + slug + "/posts/" + postId))
        .doesNotContain("제주")
        .contains(prop("og:title", "블로그"));
  }

  @Test
  void svgCoverIsServedAsPngForPreviews() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC", "카페 그림", "그림 블로그");
    String svg;
    try (InputStream in = getClass().getResourceAsStream("/cover-art/cafe-raw.svg")) {
      svg = SvgSanitizer.sanitize(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    String stored =
        storage.store(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)), "svg");
    jdbc.update("update blogs set cover_image = ? where id = ?", stored, blogId);
    String pngPath = "/api/og/covers/" + stored.replace(".svg", ".png");

    String html = page("/blog/" + slugOf(blogId));
    assertThat(html)
        .contains(prop("og:image", ORIGIN + pngPath))
        .contains(prop("og:image:width", "1200"))
        .contains(prop("og:image:height", "630"));
    // 글에 이미지가 없으면 블로그 대표 그림
    long postId = writePost(owner, blogId, "글", "본문");
    assertThat(page("/blog/" + slugOf(blogId) + "/posts/" + postId))
        .contains(prop("og:image", ORIGIN + pngPath));

    MockHttpServletResponse res =
        mockMvc
            .perform(get(pngPath).header("User-Agent", CRAWLER))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(res.getContentType()).isEqualTo("image/png");
    assertThat(res.getHeader("Cache-Control")).contains("max-age=2592000").contains("public");
    BufferedImage png = ImageIO.read(new ByteArrayInputStream(res.getContentAsByteArray()));
    assertThat(png.getWidth()).isEqualTo(1200);
    assertThat(png.getHeight()).isEqualTo(630);

    // 없는 그림이나 이상한 이름은 404
    mockMvc
        .perform(get("/api/og/covers/" + UUID.randomUUID() + ".png"))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/og/covers/not-a-cover.png")).andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/og/covers/" + stored.replace(".svg", ".svg.png")))
        .andExpect(status().isNotFound());
  }

  // ---------- 도우미 ----------

  private static String preload(String href) {
    return "<link rel=\"preload\" as=\"fetch\" crossorigin=\"anonymous\" href=\"" + href;
  }

  private static String prop(String property, String content) {
    return "<meta property=\"" + property + "\" content=\"" + content + "\" />";
  }

  private static String name(String name, String content) {
    return "<meta name=\"" + name + "\" content=\"" + content + "\" />";
  }

  private static String metaContent(String html, String property) {
    String head = "<meta property=\"" + property + "\" content=\"";
    int start = html.indexOf(head) + head.length();
    return html.substring(start, html.indexOf('"', start));
  }

  /** 미리보기 서버처럼 쿠키 없이 HTML을 받는다. */
  private String page(String url) throws Exception {
    MockHttpServletResponse res =
        mockMvc
            .perform(get(url).header("User-Agent", CRAWLER))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(res.getContentType()).startsWith("text/html");
    return res.getContentAsString(StandardCharsets.UTF_8);
  }

  private org.springframework.test.web.servlet.ResultActions call(
      User user, MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request.with(csrf()).with(auth(user)));
  }

  private static RequestPostProcessor auth(User user) {
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AuthUser(user.getId(), UserRole.USER),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
  }

  private JsonNode json(String body) {
    return jsonMapper.readTree(body);
  }

  private long createBlog(User owner, String visibility, String name, String description)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "og-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", name);
    body.put("description", description);
    body.put("visibility", visibility);
    body.put("joinPolicy", "OPEN");
    body.put("tags", List.of());
    String res =
        call(
                owner,
                post("/api/blogs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json(res).get("id").asLong();
  }

  private long writePost(User user, long blogId, String title, String content) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", title);
    body.put("content", content);
    body.put("tags", List.of());
    body.put("notice", false);
    String res =
        call(
                user,
                post("/api/blogs/" + blogId + "/posts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json(res).get("id").asLong();
  }

  private String upload(User user, byte[] png) throws Exception {
    String res =
        mockMvc
            .perform(
                multipart("/api/images")
                    .file(new MockMultipartFile("file", "a.png", "image/png", png))
                    .with(csrf())
                    .with(auth(user)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json(res).get("url").asString();
  }

  private String slugOf(long blogId) {
    return jdbc.queryForObject("select slug from blogs where id = ?", String.class, blogId);
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "o" + unique + "@example.com",
            "hash",
            "이름",
            "o" + unique,
            "01012345678",
            LocalDateTime.now()));
  }
}
