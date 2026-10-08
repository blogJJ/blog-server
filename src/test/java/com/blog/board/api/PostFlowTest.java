package com.blog.board.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
import com.blog.board.service.TestImages;
import com.blog.common.security.AuthUser;
import com.blog.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 글 쓰기·읽기·이미지를 실제 API로 확인한다 (T062~T065, T069, BRD-01~05, BRD-07, BRD-11, SC-007). */
@IntegrationTest
class PostFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;

  // ---------- 쓰기·수정·삭제 (T063) ----------

  @Test
  void memberWritesAndOutsiderCannot() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC");

    long postId = writePost(owner, blogId, "첫 글", "본문이에요", List.of("#Java", "java"), false);
    assertThat(
            jdbc.queryForObject("select post_count from blogs where id = ?", Integer.class, blogId))
        .isEqualTo(1);

    call(
            newUser(),
            post("/api/blogs/" + blogId + "/posts"),
            postBody("남의 글", "본문", List.of(), false))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/blogs/" + blogId + "/posts")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(postBody("비회원", "본문", List.of(), false)))
        .andExpect(status().isUnauthorized());

    call(null, get("/api/posts/" + postId), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("첫 글"))
        .andExpect(jsonPath("$.tags.length()").value(1))
        .andExpect(jsonPath("$.markdown").doesNotExist())
        .andExpect(jsonPath("$.canEdit").value(false));
  }

  @Test
  void validationOfTitleAndContent() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    String url = "/api/blogs/" + blogId + "/posts";

    call(owner, post(url), postBody("", "본문", List.of(), false)).andExpect(status().isBadRequest());
    call(owner, post(url), postBody("가".repeat(31), "본문", List.of(), false))
        .andExpect(status().isBadRequest());
    call(owner, post(url), postBody("제목", "가".repeat(5001), List.of(), false))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("5,000자")));
    call(owner, post(url), postBody("제목", "가".repeat(5000), List.of(), false))
        .andExpect(status().isCreated());
  }

  @Test
  void scriptInContentIsNeverServed() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long postId =
        writePost(
            owner,
            blogId,
            "XSS",
            "<script>alert(1)</script><img src=x onerror=alert(1)>",
            List.of(),
            false);

    String html = body(call(null, get("/api/posts/" + postId), null)).get("html").asString();
    assertThat(html).doesNotContain("<script").doesNotContain("onerror");
  }

  @Test
  void onlyAuthorEditsAndNoticeNeedsManagePosts() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(member, blogId);
    long postId = writePost(member, blogId, "멤버 글", "본문", List.of(), false);

    call(owner, put("/api/posts/" + postId), postBody("블로그장이 고침", "본문", List.of(), false))
        .andExpect(status().isForbidden());
    call(member, put("/api/posts/" + postId), postBody("고친 제목", "고친 본문", List.of("새태그"), false))
        .andExpect(status().isOk());
    call(member, get("/api/posts/" + postId), null)
        .andExpect(jsonPath("$.title").value("고친 제목"))
        .andExpect(jsonPath("$.markdown").value("고친 본문"))
        .andExpect(jsonPath("$.tags[0]").value("새태그"));

    call(member, post("/api/blogs/" + blogId + "/posts"), postBody("공지", "본문", List.of(), true))
        .andExpect(status().isForbidden());
    writePost(owner, blogId, "블로그장 공지", "본문", List.of(), true);
  }

  @Test
  void ownerDeletesMemberPostAndAuthorIsNotified() throws Exception {
    User owner = newUser();
    User member = newUser();
    User other = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(member, blogId);
    join(other, blogId);
    long postId = writePost(member, blogId, "지울 글", "본문", List.of(), false);

    call(other, delete("/api/posts/" + postId), null).andExpect(status().isForbidden());
    call(owner, delete("/api/posts/" + postId), null).andExpect(status().isNoContent());

    assertThat(jdbc.queryForObject("select status from posts where id = ?", String.class, postId))
        .isEqualTo("DELETED");
    assertThat(
            jdbc.queryForObject("select post_count from blogs where id = ?", Integer.class, blogId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from notifications where receiver_id = ? and type = 'POST_DELETED'",
                Integer.class,
                member.getId()))
        .isEqualTo(1);
    call(null, get("/api/posts/" + postId), null).andExpect(status().isNotFound());
  }

  @Test
  void adminCanDeleteAnyPost() throws Exception {
    User owner = newUser();
    User admin = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long postId = writePost(owner, blogId, "글", "본문", List.of(), false);

    call(admin, UserRole.ADMIN, delete("/api/posts/" + postId), null)
        .andExpect(status().isNoContent());
  }

  // ---------- 목록·상세·조회수 (T064, T065) ----------

  @Test
  void listPagesAndKeepsNoticesApart() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    for (int i = 1; i <= 12; i++) {
      writePost(owner, blogId, "글 " + i, "본문", List.of(), false);
    }
    writePost(owner, blogId, "공지 글", "본문", List.of(), true);

    call(null, get("/api/blogs/" + blogId + "/posts"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.notices[0].title").value("공지 글"))
        .andExpect(jsonPath("$.page.items.length()").value(10))
        .andExpect(jsonPath("$.page.items[0].title").value("글 12"))
        .andExpect(jsonPath("$.page.totalElements").value(12));
    call(null, get("/api/blogs/" + blogId + "/posts?page=2"), null)
        .andExpect(jsonPath("$.notices.length()").value(0))
        .andExpect(jsonPath("$.page.items.length()").value(2));
    call(null, get("/api/blogs/" + blogId + "/posts?size=20"), null)
        .andExpect(jsonPath("$.page.items.length()").value(12));
  }

  @Test
  void categoryFilter() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    jdbc.update("insert into categories (blog_id, name, sort_order) values (?, '여행', 0)", blogId);
    long categoryId =
        jdbc.queryForObject("select id from categories where blog_id = ?", Long.class, blogId);
    Map<String, Object> body = postMap("여행 글", "본문", List.of(), false);
    body.put("categoryId", categoryId);
    call(owner, post("/api/blogs/" + blogId + "/posts"), jsonMapper.writeValueAsString(body))
        .andExpect(status().isCreated());
    writePost(owner, blogId, "다른 글", "본문", List.of(), false);

    call(null, get("/api/blogs/" + blogId + "/posts?category=" + categoryId), null)
        .andExpect(jsonPath("$.page.items.length()").value(1))
        .andExpect(jsonPath("$.page.items[0].categoryName").value("여행"));
    call(null, get("/api/blogs/" + blogId + "/categories"), null)
        .andExpect(jsonPath("$[0].name").value("여행"));

    // 다른 블로그의 카테고리는 고를 수 없다
    long otherBlog = createBlog(owner, "PRIVATE");
    call(owner, post("/api/blogs/" + otherBlog + "/posts"), jsonMapper.writeValueAsString(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void privateBlogPostsAreMembersOnly() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PRIVATE");
    long postId = writePost(owner, blogId, "비밀 글", "본문", List.of(), false);

    call(null, get("/api/posts/" + postId), null).andExpect(status().isForbidden());
    call(null, get("/api/blogs/" + blogId + "/posts"), null).andExpect(status().isForbidden());
    call(newUser(), get("/api/posts/" + postId), null).andExpect(status().isForbidden());
    call(owner, get("/api/posts/" + postId), null).andExpect(status().isOk());
  }

  @Test
  void viewCountsOncePerPersonPerDayAndNotForAuthor() throws Exception {
    User owner = newUser();
    User reader = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long postId = writePost(owner, blogId, "조회 글", "본문", List.of(), false);

    call(owner, get("/api/posts/" + postId), null);
    call(reader, get("/api/posts/" + postId), null).andExpect(jsonPath("$.viewCount").value(1));
    call(reader, get("/api/posts/" + postId), null).andExpect(jsonPath("$.viewCount").value(1));
    mockMvc.perform(get("/api/posts/" + postId).header("User-Agent", "browser-a"));
    mockMvc.perform(get("/api/posts/" + postId).header("User-Agent", "browser-a"));
    mockMvc
        .perform(get("/api/posts/" + postId).header("User-Agent", "browser-b"))
        .andExpect(jsonPath("$.viewCount").value(3));
  }

  // ---------- 이미지 (T062) ----------

  @Test
  void uploadValidatesTypeAndServesImage() throws Exception {
    User user = newUser();

    JsonNode res =
        body(
            upload(user, "photo.png", "image/png", TestImages.png())
                .andExpect(status().isCreated()));
    String url = res.get("url").asString();
    assertThat(url).matches("/api/images/[0-9a-f-]{36}\\.png");
    mockMvc
        .perform(get(url))
        .andExpect(status().isOk())
        .andExpect(content().contentType("image/png"))
        .andExpect(header().string("Cache-Control", containsString("max-age")));
    assertThat(
            jdbc.queryForObject(
                "select original_name from post_images where stored_name = ?",
                String.class,
                url.substring(12)))
        .isEqualTo("photo.png");

    // svg, 확장자와 내용이 다른 파일, 확장자와 MIME이 다른 파일은 막는다
    upload(user, "x.svg", "image/svg+xml", "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes())
        .andExpect(status().isBadRequest());
    upload(user, "fake.png", "image/png", "not an image".getBytes())
        .andExpect(status().isBadRequest());
    upload(user, "photo.jpg", "image/jpeg", TestImages.png()).andExpect(status().isBadRequest());
    upload(user, "photo.png", "image/gif", TestImages.png()).andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/images/../../etc/passwd")).andExpect(status().is4xxClientError());
  }

  @Test
  void uploadedJpegHasNoExif() throws Exception {
    User user = newUser();
    String url =
        body(upload(user, "phone.jpg", "image/jpeg", TestImages.jpegWithExif("GPS-37.5,127.0")))
            .get("url")
            .asString();

    byte[] served = mockMvc.perform(get(url)).andReturn().getResponse().getContentAsByteArray();
    assertThat(new String(served, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain("GPS-37.5")
        .doesNotContain("Exif");
  }

  @Test
  void postAttachesOnlyOwnImagesUpToTen() throws Exception {
    User owner = newUser();
    User other = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    String mine = body(upload(owner, "a.png", "image/png", TestImages.png())).get("url").asString();
    String theirs =
        body(upload(other, "b.png", "image/png", TestImages.png())).get("url").asString();

    long postId =
        writePost(
            owner, blogId, "사진 글", "![](" + mine + ")\n\n![](" + theirs + ")", List.of(), false);

    assertThat(postIdOf(mine)).isEqualTo(postId);
    assertThat(postIdOf(theirs)).isNull();
    call(null, get("/api/blogs/" + blogId + "/posts"), null)
        .andExpect(jsonPath("$.page.items[0].thumbnail").value(mine));

    // 본문에서 빼면 연결이 끊긴다
    call(owner, put("/api/posts/" + postId), postBody("사진 글", "사진 없음", List.of(), false))
        .andExpect(status().isOk());
    assertThat(postIdOf(mine)).isNull();

    StringBuilder eleven = new StringBuilder();
    for (int i = 0; i < 11; i++) {
      eleven.append("![](/api/images/").append(UUID.randomUUID()).append(".png)\n");
    }
    call(
            owner,
            post("/api/blogs/" + blogId + "/posts"),
            postBody("많음", eleven.toString(), List.of(), false))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("10장")));
  }

  @Test
  void coverImageIsSetByEditorsOnly() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    MockMultipartFile file =
        new MockMultipartFile("file", "cover.png", "image/png", TestImages.png());

    mockMvc
        .perform(
            multipart("/api/blogs/" + blogId + "/cover")
                .file(file)
                .with(
                    r -> {
                      r.setMethod("PUT");
                      return r;
                    })
                .with(csrf())
                .with(auth(newUser(), UserRole.USER)))
        .andExpect(status().isForbidden());
    String cover =
        body(mockMvc.perform(
                multipart("/api/blogs/" + blogId + "/cover")
                    .file(file)
                    .with(
                        r -> {
                          r.setMethod("PUT");
                          return r;
                        })
                    .with(csrf())
                    .with(auth(owner, UserRole.USER))))
            .get("coverImage")
            .asString();
    assertThat(cover).startsWith("/api/images/");
    call(null, get("/api/blogs/by-slug/" + slugOf(blogId)), null)
        .andExpect(jsonPath("$.coverImage").value(cover));
  }

  // ---------- 공유 미리보기 (T069) ----------

  @Test
  void postPageFillsOgTagsOnlyForPublicPosts() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long postId = writePost(owner, blogId, "공유할 <글>", "첫 문단이에요", List.of(), false);
    long privateBlog = createBlog(owner, "PRIVATE");
    long secret = writePost(owner, privateBlog, "비밀 제목", "본문", List.of(), false);

    mockMvc
        .perform(get("/blog/" + slugOf(blogId) + "/posts/" + postId))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("property=\"og:title\" content=\"공유할 &lt;글&gt;\"")))
        .andExpect(content().string(containsString("og:description\" content=\"첫 문단이에요\"")));
    mockMvc
        .perform(get("/blog/" + slugOf(privateBlog) + "/posts/" + secret))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("비밀 제목"))))
        .andExpect(content().string(not(containsString("og:title"))));
  }

  // ---------- 도우미 ----------

  private Long postIdOf(String url) {
    return jdbc.queryForObject(
        "select post_id from post_images where stored_name = ?", Long.class, url.substring(12));
  }

  private ResultActions upload(User user, String name, String type, byte[] data) throws Exception {
    return mockMvc.perform(
        multipart("/api/images")
            .file(new MockMultipartFile("file", name, type, data))
            .with(csrf())
            .with(auth(user, UserRole.USER)));
  }

  private ResultActions call(User user, MockHttpServletRequestBuilder request, String json)
      throws Exception {
    return call(user, UserRole.USER, request, json);
  }

  private ResultActions call(
      User user, UserRole role, MockHttpServletRequestBuilder request, String json)
      throws Exception {
    request.with(csrf());
    if (user != null) {
      request.with(auth(user, role));
    }
    if (json != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mockMvc.perform(request);
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(
      User user, UserRole role) {
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AuthUser(user.getId(), role),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
  }

  private JsonNode body(ResultActions result) throws Exception {
    return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private long createBlog(User owner, String visibility) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "p-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "글 시험 블로그");
    body.put("visibility", visibility);
    body.put("joinPolicy", "OPEN");
    body.put("tags", List.of());
    return body(call(owner, post("/api/blogs"), jsonMapper.writeValueAsString(body))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private void join(User user, long blogId) throws Exception {
    call(user, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
  }

  private long writePost(
      User user, long blogId, String title, String content, List<String> tags, boolean notice)
      throws Exception {
    return body(call(
                user,
                post("/api/blogs/" + blogId + "/posts"),
                postBody(title, content, tags, notice))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private String postBody(String title, String content, List<String> tags, boolean notice) {
    return jsonMapper.writeValueAsString(postMap(title, content, tags, notice));
  }

  private Map<String, Object> postMap(
      String title, String content, List<String> tags, boolean notice) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", title);
    body.put("content", content);
    body.put("tags", new ArrayList<>(tags));
    body.put("notice", notice);
    return body;
  }

  private String slugOf(long blogId) {
    return jdbc.queryForObject("select slug from blogs where id = ?", String.class, blogId);
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "p" + unique + "@example.com",
            "hash",
            "이름",
            "p" + unique,
            "01012345678",
            LocalDateTime.now()));
  }
}
