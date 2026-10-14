package com.blog.main.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
import com.blog.common.security.AuthUser;
import com.blog.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 메인 피드·통합 검색·최근 검색어를 실제 API로 확인한다 (T106~T108, BRD-08, BRD-09, D-65, D-73, D-89). */
@IntegrationTest
class FeedSearchFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;

  /** spec.md US7 Independent Test: A가 B를 팔로우하고 C 블로그를 구독 → 각 탭에 나온다 */
  @Test
  void followingAndSubscribedTabsShowTheirOwnPosts() throws Exception {
    User a = newUser();
    User b = newUser();
    User c = newUser();
    long bBlog = createBlog(b, "PUBLIC");
    long cBlog = createBlog(c, "PUBLIC");
    call(a, post("/api/users/" + b.getId() + "/follow"), null).andExpect(status().isOk());
    call(a, post("/api/blogs/" + cBlog + "/subscribe"), null).andExpect(status().isOk());
    long bPost = writePost(b, bBlog, "팔로우 탭 글", List.of());
    long cPost = writePost(c, cBlog, "구독 탭 글", List.of());

    JsonNode following = body(call(a, get("/api/feed?tab=following"), null));
    assertThat(ids(following)).containsExactly(bPost);
    JsonNode card = following.get("items").get(0);
    assertThat(card.get("blogName").asString()).isEqualTo("피드 시험");
    assertThat(card.get("blogSlug").asString()).isNotBlank();
    assertThat(card.get("authorId").asLong()).isEqualTo(b.getId());
    assertThat(ids(body(call(a, get("/api/feed?tab=subscribed"), null)))).containsExactly(cPost);

    // 최신 탭은 전체 공개 글. 방금 쓴 글이 맨 위
    assertThat(ids(body(call(null, get("/api/feed?tab=latest"), null))).subList(0, 2))
        .containsExactly(cPost, bPost);
    call(null, get("/api/feed?tab=following"), null).andExpect(status().isUnauthorized());
  }

  /** 목록 미리보기 (D-149): 마크다운 기호와 이미지는 빼고 글자만, 본문 첫머리의 제목은 빼고, 100자를 넘으면 자르고 … */
  @Test
  void feedCardCarriesPlainTextExcerpt() throws Exception {
    User writer = newUser();
    long blogId = createBlog(writer, "PUBLIC");
    String longText = "가".repeat(150);
    long shortPost =
        writePost(writer, blogId, "짧은 미리보기", List.of(), "# 짧은 미리보기\n\n**굵게** 쓴 _본문_ ![](/x.png)");
    long longPost = writePost(writer, blogId, "긴 미리보기", List.of(), longText);

    JsonNode items = body(call(null, get("/api/feed?tab=latest&size=30"), null)).get("items");
    Map<Long, String> excerpts = new LinkedHashMap<>();
    items.forEach(i -> excerpts.put(i.get("id").asLong(), i.get("excerpt").asString()));
    assertThat(excerpts)
        .containsEntry(shortPost, "굵게 쓴 본문")
        .containsEntry(longPost, "가".repeat(100) + "…");
  }

  @Test
  void popularIsByLikesAndPrivateOrBlockedPostsAreLeftOut() throws Exception {
    User owner = newUser();
    User fan = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long privateBlog = createBlog(owner, "PRIVATE");
    long liked = writePost(owner, blogId, "좋아요 많은 글", List.of());
    long secret = writePost(owner, privateBlog, "비공개 글", List.of());
    // 인기 탭 맨 위에 오도록 좋아요 수를 크게 둔다
    jdbc.update("update posts set like_count = 1000000 where id = ?", liked);

    JsonNode popular = body(call(fan, get("/api/feed?tab=popular&size=30"), null));
    assertThat(ids(popular).get(0)).isEqualTo(liked);
    assertThat(ids(body(call(fan, get("/api/feed?tab=latest&size=30"), null))))
        .doesNotContain(secret);

    // 차단한 회원의 글은 모든 탭에서 빠진다 (SOC-05)
    call(fan, post("/api/users/" + owner.getId() + "/block"), null)
        .andExpect(status().isNoContent());
    assertThat(ids(body(call(fan, get("/api/feed?tab=popular&size=30"), null))))
        .doesNotContain(liked);
    assertThat(ids(body(call(null, get("/api/feed?tab=popular&size=30"), null))).get(0))
        .isEqualTo(liked);
  }

  @Test
  void subscribedTabDropsPrivateBlogForNonMembers() throws Exception {
    User owner = newUser();
    User reader = newUser();
    User member = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    call(member, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
    call(reader, post("/api/blogs/" + blogId + "/subscribe"), null).andExpect(status().isOk());
    call(member, post("/api/blogs/" + blogId + "/subscribe"), null).andExpect(status().isOk());
    long postId = writePost(owner, blogId, "비공개 전환 전 글", List.of());
    assertThat(ids(body(call(reader, get("/api/feed?tab=subscribed"), null)))).contains(postId);

    call(owner, put("/api/blogs/" + blogId), updateBody("PRIVATE")).andExpect(status().isOk());
    assertThat(ids(body(call(reader, get("/api/feed?tab=subscribed"), null))))
        .doesNotContain(postId);
    assertThat(ids(body(call(member, get("/api/feed?tab=subscribed"), null)))).contains(postId);
  }

  @Test
  void suspendedMemberDoesNotSeePrivateBlogPostsInFeed() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    call(member, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
    call(member, post("/api/blogs/" + blogId + "/subscribe"), null).andExpect(status().isOk());
    call(member, post("/api/users/" + owner.getId() + "/follow"), null)
        .andExpect(status().is2xxSuccessful());
    call(owner, put("/api/blogs/" + blogId), updateBody("PRIVATE")).andExpect(status().isOk());
    long postId = writePost(owner, blogId, "멤버만 보는 글", List.of());
    assertThat(ids(body(call(member, get("/api/feed?tab=subscribed"), null)))).contains(postId);

    call(
            owner,
            post("/api/blogs/" + blogId + "/members/" + member.getId() + "/suspend"),
            "{\"reason\":\"도배\",\"days\":3}")
        .andExpect(status().is2xxSuccessful());
    assertThat(ids(body(call(member, get("/api/feed?tab=subscribed"), null))))
        .doesNotContain(postId);
    assertThat(ids(body(call(member, get("/api/feed?tab=following"), null))))
        .doesNotContain(postId);

    call(owner, post("/api/blogs/" + blogId + "/members/" + member.getId() + "/release"), null)
        .andExpect(status().is2xxSuccessful());
    assertThat(ids(body(call(member, get("/api/feed?tab=following"), null)))).contains(postId);
  }

  @Test
  void searchFindsTitlesBlogsTagsAndNicknames() throws Exception {
    String word = word();
    User writer = newUser();
    long blogId = createBlog(writer, "PUBLIC", word + " 모임");
    long privateBlog = createBlog(writer, "PRIVATE", word + " 비밀");
    long first = writePost(writer, blogId, word + " 첫 글", List.of(word));
    long second = writePost(writer, blogId, "두 번째 " + word, List.of());
    writePost(writer, privateBlog, word + " 숨은 글", List.of(word));
    jdbc.update("update posts set like_count = 5 where id = ?", first);

    // 글 제목 (FULLTEXT ngram). 비공개 블로그 글은 없다
    JsonNode byTitle = body(call(null, get("/api/search?q=" + word + "&sort=latest"), null));
    assertThat(byTitle.get("type").asString()).isEqualTo("post");
    assertThat(ids(byTitle)).containsExactly(second, first);
    assertThat(byTitle.get("message").isNull()).isTrue();
    assertThat(ids(body(call(null, get("/api/search?q=" + word + "&sort=popular"), null))))
        .containsExactly(first, second);

    // 블로그 (이름·소개)
    JsonNode blogs = body(call(null, get("/api/search?type=blog&q=" + word), null));
    assertThat(ids(blogs)).containsExactly(blogId);

    // 태그·닉네임은 일치 검색
    assertThat(ids(body(call(null, get("/api/search?type=tag").param("q", "#" + word), null))))
        .containsExactly(first);
    JsonNode byNickname =
        body(call(null, get("/api/search?type=nickname&q=" + writer.getNickname()), null));
    assertThat(byNickname.get("user").get("id").asLong()).isEqualTo(writer.getId());
    assertThat(byNickname.get("user").has("email")).isFalse();
    assertThat(ids(byNickname)).containsExactlyInAnyOrder(first, second);
    call(null, get("/api/search?type=nickname&q=" + writer.getNickname().substring(0, 4)), null)
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.message").value("검색 결과가 없습니다"));

    // 차단한 회원의 글은 나오지 않는다
    User blocker = newUser();
    call(blocker, post("/api/users/" + writer.getId() + "/block"), null)
        .andExpect(status().isNoContent());
    call(blocker, get("/api/search?q=" + word), null)
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.message").value("검색 결과가 없습니다"));
    call(blocker, get("/api/search?type=nickname&q=" + writer.getNickname()), null)
        .andExpect(jsonPath("$.user").doesNotExist());
  }

  @Test
  void searchRejectsTooShortOrSymbolOnlyQueries() throws Exception {
    for (String q : List.of("a", "   ", "!!@@", "가".repeat(21))) {
      call(null, get("/api/search").param("q", q), null).andExpect(status().isBadRequest());
    }
    call(null, get("/api/search"), null).andExpect(status().isBadRequest());
    call(null, get("/api/search").param("q", " 없는검색어" + word() + " "), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("검색 결과가 없습니다"));
  }

  @Test
  void recentSearchesKeepTenNewestPerUser() throws Exception {
    User me = newUser();
    User other = newUser();
    for (int i = 0; i < 11; i++) {
      call(me, get("/api/search").param("q", "검색어" + i), null).andExpect(status().isOk());
    }
    // 같은 검색어는 한 줄로, 시각만 바뀌어 맨 위로 온다
    call(me, get("/api/search").param("q", "검색어5"), null).andExpect(status().isOk());

    JsonNode rows = body(call(me, get("/api/me/recent-searches"), null));
    List<String> words = rows.valueStream().map(r -> r.get("keyword").asString()).toList();
    assertThat(words).hasSize(10).startsWith("검색어5", "검색어10").doesNotContain("검색어0");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from recent_searches where user_id = ?",
                Integer.class,
                me.getId()))
        .isEqualTo(10);
    call(null, get("/api/search").param("q", "비회원검색"), null).andExpect(status().isOk());

    long id = rows.get(0).get("id").asLong();
    call(other, delete("/api/me/recent-searches/" + id), null).andExpect(status().isNotFound());
    call(me, delete("/api/me/recent-searches/" + id), null).andExpect(status().isNoContent());
    call(me, get("/api/me/recent-searches"), null).andExpect(jsonPath("$.length()").value(9));
    call(me, delete("/api/me/recent-searches"), null).andExpect(jsonPath("$.deleted").value(9));
    call(me, get("/api/me/recent-searches"), null).andExpect(jsonPath("$.length()").value(0));
    call(null, get("/api/me/recent-searches"), null).andExpect(status().isUnauthorized());
  }

  // ---------- 도우미 ----------

  private static String word() {
    // ngram 검색이 다른 테스트의 글과 섞이지 않게 영문 소문자로 만든 한 번만 쓰는 단어
    StringBuilder sb = new StringBuilder("zq");
    for (int i = 0; i < 6; i++) {
      sb.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
    }
    return sb.toString();
  }

  private static List<Long> ids(JsonNode page) {
    return page.get("items").valueStream().map(n -> n.get("id").asLong()).toList();
  }

  private ResultActions call(User user, MockHttpServletRequestBuilder request, String json)
      throws Exception {
    request.with(csrf());
    if (user != null) {
      request.with(
          authentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  new AuthUser(user.getId(), UserRole.USER),
                  null,
                  List.of(new SimpleGrantedAuthority("ROLE_USER")))));
    }
    if (json != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mockMvc.perform(request);
  }

  private JsonNode body(ResultActions result) throws Exception {
    return jsonMapper.readTree(
        result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private long createBlog(User owner, String visibility) throws Exception {
    return createBlog(owner, visibility, "피드 시험");
  }

  private long createBlog(User owner, String visibility, String name) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "f-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", name);
    body.put("visibility", visibility);
    body.put("joinPolicy", "OPEN");
    body.put("tags", List.of());
    return jsonMapper
        .readTree(
            call(owner, post("/api/blogs"), jsonMapper.writeValueAsString(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("id")
        .asLong();
  }

  private String updateBody(String visibility) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", "피드 시험");
    body.put("visibility", visibility);
    body.put("joinPolicy", "OPEN");
    body.put("tags", List.of());
    return jsonMapper.writeValueAsString(body);
  }

  private long writePost(User user, long blogId, String title, List<String> tags) throws Exception {
    return writePost(user, blogId, title, tags, "본문");
  }

  private long writePost(User user, long blogId, String title, List<String> tags, String content)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", title);
    body.put("content", content);
    body.put("tags", tags);
    body.put("notice", false);
    return jsonMapper
        .readTree(
            call(user, post("/api/blogs/" + blogId + "/posts"), jsonMapper.writeValueAsString(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("id")
        .asLong();
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "f" + unique + "@example.com",
            "hash",
            "이름",
            "f" + unique,
            "01012345678",
            LocalDateTime.now()));
  }
}
