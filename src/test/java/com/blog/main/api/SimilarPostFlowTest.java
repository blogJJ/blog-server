package com.blog.main.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 비슷한 글 (T168). 비회원이 볼 수 있는 글만 추천하는지(비공개·일부 공개·숨김·폐쇄·삭제·공지 제외), 다른 블로그 글이 먼저인지, 개수 제한과 빈 결과를 확인한다.
 * 다른 테스트의 글과 섞이지 않게 테스트마다 새 태그와 제목 낱말을 쓴다.
 */
@IntegrationTest
class SimilarPostFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;

  @Test
  void recommendsOnlyPostsLoggedOutVisitorsCanSee() throws Exception {
    String tag = uniqueTag();
    User me = newUser();
    long myBlog = createBlog(me, "PUBLIC");
    long source = writePost(me, myBlog, uniqueWord(), "본문", List.of(tag), false);
    long sameBlog = writePost(me, myBlog, "같은 블로그 글", "본문", List.of(tag), false);
    long myNotice = writePost(me, myBlog, "블로그 공지", "본문", List.of(tag), true);

    User other = newUser();
    long otherBlog = createBlog(other, "PUBLIC");
    long visible =
        writePost(
            other,
            otherBlog,
            "다른 블로그 글",
            "# 다른 블로그 글\n\n**굵은** 첫 문장과 " + "가".repeat(100),
            List.of(tag),
            false);
    long hiddenPost = writePost(other, otherBlog, "관리자 숨김 글", "본문", List.of(tag), false);
    jdbc.update("update posts set status = 'HIDDEN' where id = ?", hiddenPost);
    long deletedPost = writePost(other, otherBlog, "지운 글", "본문", List.of(tag), false);
    jdbc.update(
        "update posts set status = 'DELETED', deleted_at = now() where id = ?", deletedPost);

    List<Long> excluded = new ArrayList<>(List.of(myNotice, hiddenPost, deletedPost));
    for (String visibility : List.of("PRIVATE", "LINK_ONLY")) {
      User u = newUser();
      excluded.add(writePost(u, createBlog(u, visibility), "숨은 글", "본문", List.of(tag), false));
    }
    User closer = newUser();
    long closedBlog = createBlog(closer, "PUBLIC");
    excluded.add(writePost(closer, closedBlog, "폐쇄된 블로그 글", "본문", List.of(tag), false));
    jdbc.update("update blogs set status = 'CLOSED', closed_at = now() where id = ?", closedBlog);
    User hider = newUser();
    long hiddenBlog = createBlog(hider, "PUBLIC");
    excluded.add(writePost(hider, hiddenBlog, "숨긴 블로그 글", "본문", List.of(tag), false));
    jdbc.update("update blogs set is_hidden = true where id = ?", hiddenBlog);

    JsonNode res = similar(source, 8);
    // 다른 블로그 글이 먼저, 같은 블로그 글은 채우기용으로 뒤에
    assertThat(ids(res)).containsExactly(visible, sameBlog).doesNotContainAnyElementsOf(excluded);

    JsonNode first = res.get(0);
    assertThat(first.get("title").asString()).isEqualTo("다른 블로그 글");
    assertThat(first.get("blogId").asLong()).isEqualTo(otherBlog);
    assertThat(first.get("blogSlug").asString()).isEqualTo(slugOf(otherBlog));
    assertThat(first.get("blogName").asString()).isNotBlank();
    assertThat(first.get("excerpt").asString()).startsWith("굵은 첫 문장과 가").endsWith("…");
    String excerpt = first.get("excerpt").asString();
    assertThat(excerpt.codePointCount(0, excerpt.length())).isEqualTo(61); // 60자 + …
    assertThat(first.get("thumbnail").isNull()).isTrue();
    assertThat(first.has("authorId")).isFalse();
  }

  @Test
  void sourceMustBeVisibleToTheViewer() throws Exception {
    User owner = newUser();
    long privateBlog = createBlog(owner, "PRIVATE");
    long secret = writePost(owner, privateBlog, "비밀 글", "본문", List.of(uniqueTag()), false);
    mockMvc.perform(get("/api/posts/" + secret + "/similar")).andExpect(status().isForbidden());
    call(owner, get("/api/posts/" + secret + "/similar")).andExpect(status().isOk());
    mockMvc.perform(get("/api/posts/987654321/similar")).andExpect(status().isNotFound());

    // 일부 공개 블로그 글은 공유 링크 값이 있으면 된다
    long linkBlog = createBlog(owner, "LINK_ONLY");
    long linked = writePost(owner, linkBlog, "링크 글", "본문", List.of(uniqueTag()), false);
    String key =
        jdbc.queryForObject("select share_key from blogs where id = ?", String.class, linkBlog);
    mockMvc.perform(get("/api/posts/" + linked + "/similar")).andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/posts/" + linked + "/similar").param("share", key))
        .andExpect(status().isOk());
  }

  @Test
  void limitIsAppliedAndClamped() throws Exception {
    String tag = uniqueTag();
    User me = newUser();
    long source = writePost(me, createBlog(me, "PUBLIC"), uniqueWord(), "본문", List.of(tag), false);
    for (int i = 0; i < 4; i++) {
      User u = newUser();
      long blog = createBlog(u, "PUBLIC");
      for (int j = 0; j < 3; j++) {
        writePost(u, blog, "글 " + i + "-" + j, "본문", List.of(tag), false);
      }
    }
    assertThat(similar(source, null).size()).isEqualTo(4); // 기본 4
    assertThat(similar(source, 2).size()).isEqualTo(2);
    assertThat(similar(source, 100).size()).isEqualTo(8); // 최대 8
    assertThat(similar(source, 0).size()).isEqualTo(1);
    // 한 블로그에서는 두 개까지 먼저, 그래서 네 블로그가 고루 나온다
    JsonNode eight = similar(source, 8);
    Map<Long, Integer> perBlog = new LinkedHashMap<>();
    eight.forEach(n -> perBlog.merge(n.get("blogId").asLong(), 1, Integer::sum));
    assertThat(perBlog).hasSize(4).allSatisfy((blog, count) -> assertThat(count).isEqualTo(2));
  }

  @Test
  void emptyWhenNothingIsSimilar() throws Exception {
    User me = newUser();
    long source =
        writePost(
            me,
            createBlog(me, "PUBLIC"),
            uniqueWord() + " " + uniqueWord(),
            "본문",
            List.of(uniqueTag()),
            false);
    JsonNode res = similar(source, 4);
    assertThat(res.isArray()).isTrue();
    assertThat(res.size()).isZero();
  }

  @Test
  void titleSimilarityFindsUntaggedPosts() throws Exception {
    String word = uniqueWord();
    User me = newUser();
    long source =
        writePost(me, createBlog(me, "PUBLIC"), word + " " + uniqueWord(), "본문", List.of(), false);
    User other = newUser();
    long match =
        writePost(other, createBlog(other, "PUBLIC"), word + " 둘째 날", "본문", List.of(), false);
    assertThat(ids(similar(source, 4))).containsExactly(match);
  }

  @Test
  void hidesAuthorsTheViewerBlocked() throws Exception {
    String tag = uniqueTag();
    User me = newUser();
    long source = writePost(me, createBlog(me, "PUBLIC"), uniqueWord(), "본문", List.of(tag), false);
    User blocked = newUser();
    long blockedPost =
        writePost(blocked, createBlog(blocked, "PUBLIC"), "차단한 사람 글", "본문", List.of(tag), false);
    User fine = newUser();
    long finePost = writePost(fine, createBlog(fine, "PUBLIC"), "괜찮은 글", "본문", List.of(tag), false);
    User viewer = newUser();
    jdbc.update(
        "insert into user_blocks (blocker_id, blocked_id) values (?, ?)",
        viewer.getId(),
        blocked.getId());

    assertThat(ids(similar(source, 4))).containsExactlyInAnyOrder(blockedPost, finePost);
    String body =
        call(viewer, get("/api/posts/" + source + "/similar"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(ids(jsonMapper.readTree(body))).containsExactly(finePost);
  }

  private JsonNode similar(long postId, Integer limit) throws Exception {
    MockHttpServletRequestBuilder req = get("/api/posts/" + postId + "/similar");
    if (limit != null) {
      req.param("limit", String.valueOf(limit));
    }
    var res = mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse();
    assertThat(res.getHeader("Cache-Control")).contains("private").contains("max-age=60");
    return jsonMapper.readTree(res.getContentAsString());
  }

  private static List<Long> ids(JsonNode array) {
    List<Long> ids = new ArrayList<>();
    array.forEach(n -> ids.add(n.get("id").asLong()));
    return ids;
  }

  private static String uniqueTag() {
    return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  /** 다른 테스트의 제목과 겹치지 않는 영문 낱말(ngram 검색에 걸리게 소문자만). */
  private static String uniqueWord() {
    StringBuilder sb = new StringBuilder();
    for (char c : UUID.randomUUID().toString().replace("-", "").substring(0, 8).toCharArray()) {
      sb.append((char) ('a' + Character.digit(c, 16) % 26));
    }
    return "q" + sb;
  }

  private ResultActions call(User user, MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request.with(csrf()).with(auth(user)));
  }

  private static RequestPostProcessor auth(User user) {
    return authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AuthUser(user.getId(), UserRole.USER),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
  }

  private long createBlog(User owner, String visibility) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "sim-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "블로그 " + visibility);
    body.put("description", null);
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
    return jsonMapper.readTree(res).get("id").asLong();
  }

  private long writePost(
      User user, long blogId, String title, String content, List<String> tags, boolean notice)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", title);
    body.put("content", content);
    body.put("tags", tags);
    body.put("notice", notice);
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
    return jsonMapper.readTree(res).get("id").asLong();
  }

  private String slugOf(long blogId) {
    return jdbc.queryForObject("select slug from blogs where id = ?", String.class, blogId);
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "s" + unique + "@example.com",
            "hash",
            "이름",
            "s" + unique,
            "01012345678",
            LocalDateTime.now()));
  }
}
