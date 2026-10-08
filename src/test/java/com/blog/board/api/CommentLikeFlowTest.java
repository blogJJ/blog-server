package com.blog.board.api;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 댓글·대댓글·좋아요·카테고리 관리를 실제 API로 확인한다 (T066~T068, BRD-03, BRD-06, D-78, D-87). */
@IntegrationTest
class CommentLikeFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;

  // ---------- 댓글 (T067) ----------

  @Test
  void anyMemberCommentsAndPostAuthorIsNotified() throws Exception {
    User owner = newUser();
    User reader = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long postId = writePost(owner, blogId, "글");

    long commentId = comment(reader, postId, "좋은 글이에요", null);

    assertThat(count("select comment_count from posts where id = ?", postId)).isEqualTo(1);
    assertThat(notifications(owner, "COMMENT")).isEqualTo(1);
    call(null, get("/api/posts/" + postId + "/comments"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(commentId))
        .andExpect(jsonPath("$.items[0].authorNickname").value(reader.getNickname()))
        .andExpect(jsonPath("$.items[0].content").value("좋은 글이에요"))
        .andExpect(jsonPath("$.items[0].canEdit").value(false));
    call(reader, get("/api/posts/" + postId + "/comments"), null)
        .andExpect(jsonPath("$.items[0].canEdit").value(true));
    call(reader, get("/api/posts/" + postId), null).andExpect(jsonPath("$.commentCount").value(1));
  }

  @Test
  void replyToReplyStaysOneLevelAndMentionsTheUser() throws Exception {
    User owner = newUser();
    User a = newUser();
    User b = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long postId = writePost(owner, blogId, "글");
    long root = comment(a, postId, "첫 댓글", null);
    long reply = comment(b, postId, "답글", root);
    long replyToReply = comment(a, postId, "답글의 답글", reply);

    assertThat(
            jdbc.queryForObject(
                "select parent_id from comments where id = ?", Long.class, replyToReply))
        .isEqualTo(root);
    call(null, get("/api/posts/" + postId + "/comments"), null)
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].replies.length()").value(2))
        .andExpect(jsonPath("$.items[0].replies[0].replyToNickname").value(a.getNickname()))
        .andExpect(jsonPath("$.items[0].replies[1].replyToNickname").value(b.getNickname()))
        .andExpect(jsonPath("$.items[0].replies[1].parentId").value(root));

    // b는 a의 답글을 받고, a는 b의 답글을 받는다. 글 작성자에게는 댓글 알림 3개
    assertThat(notifications(a, "REPLY")).isEqualTo(1);
    assertThat(notifications(b, "REPLY")).isEqualTo(1);
    assertThat(notifications(owner, "COMMENT")).isEqualTo(3);
  }

  @Test
  void postAuthorGetsOnlyReplyWhenRepliedTo() throws Exception {
    User owner = newUser();
    User a = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long postId = writePost(owner, blogId, "글");
    long root = comment(owner, postId, "작성자 댓글", null);
    comment(a, postId, "작성자에게 답글", root);

    assertThat(notifications(owner, "REPLY")).isEqualTo(1);
    assertThat(notifications(owner, "COMMENT")).isZero();
  }

  @Test
  void validationAndWhoCanComment() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long postId = writePost(owner, blogId, "글");
    String url = "/api/posts/" + postId + "/comments";

    call(owner, post(url), commentBody("   ", null)).andExpect(status().isBadRequest());
    call(owner, post(url), commentBody("가".repeat(501), null)).andExpect(status().isBadRequest());
    call(owner, post(url), commentBody("가".repeat(500), null)).andExpect(status().isCreated());
    call(null, post(url), commentBody("비회원", null)).andExpect(status().isUnauthorized());
    call(newAdmin(), UserRole.ADMIN, post(url), commentBody("관리자", null))
        .andExpect(status().isForbidden());

    User suspended = newUser();
    jdbc.update(
        "update users set suspended_until = ? where id = ?",
        LocalDateTime.now().plusDays(3),
        suspended.getId());
    call(suspended, post(url), commentBody("정지", null)).andExpect(status().isForbidden());
  }

  @Test
  void privateBlogCommentsAreMembersOnly() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PRIVATE");
    long postId = writePost(owner, blogId, "비밀 글");
    comment(owner, postId, "멤버 댓글", null);

    call(null, get("/api/posts/" + postId + "/comments"), null).andExpect(status().isForbidden());
    call(newUser(), post("/api/posts/" + postId + "/comments"), commentBody("밖", null))
        .andExpect(status().isForbidden());
  }

  @Test
  void onlyAuthorEditsAndEditedIsShown() throws Exception {
    User owner = newUser();
    User a = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long postId = writePost(owner, blogId, "글");
    long id = comment(a, postId, "처음", null);

    call(owner, put("/api/comments/" + id), commentBody("블로그장이 고침", null))
        .andExpect(status().isForbidden());
    call(a, put("/api/comments/" + id), commentBody("고쳤어요", null)).andExpect(status().isOk());
    call(null, get("/api/posts/" + postId + "/comments"), null)
        .andExpect(jsonPath("$.items[0].content").value("고쳤어요"))
        .andExpect(jsonPath("$.items[0].edited").value(true));
  }

  @Test
  void deletingRootWithRepliesLeavesPlaceholder() throws Exception {
    User owner = newUser();
    User a = newUser();
    User b = newUser();
    User member = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(member, blogId);
    long postId = writePost(owner, blogId, "글");
    long root = comment(a, postId, "첫 댓글", null);
    comment(b, postId, "답글", root);
    long lonely = comment(b, postId, "답글 없는 댓글", null);

    call(member, delete("/api/comments/" + root), null).andExpect(status().isForbidden());
    call(a, delete("/api/comments/" + root), null).andExpect(status().isNoContent());
    // 블로그장은 자기 블로그의 남의 댓글을 지울 수 있다
    call(owner, delete("/api/comments/" + lonely), null).andExpect(status().isNoContent());

    call(null, get("/api/posts/" + postId + "/comments"), null)
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].deleted").value(true))
        .andExpect(jsonPath("$.items[0].content").doesNotExist())
        .andExpect(jsonPath("$.items[0].authorNickname").doesNotExist())
        .andExpect(jsonPath("$.items[0].replies[0].content").value("답글"));
    assertThat(count("select comment_count from posts where id = ?", postId)).isEqualTo(1);

    // 답글이 없는 지운 댓글에는 답할 수 없다
    call(b, post("/api/posts/" + postId + "/comments"), commentBody("늦은 답", root))
        .andExpect(status().isBadRequest());
  }

  // ---------- 좋아요 (T068) ----------

  @Test
  void likeTogglesAndNotifiesOnce() throws Exception {
    User owner = newUser();
    User reader = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long postId = writePost(owner, blogId, "글");
    String url = "/api/posts/" + postId + "/like";
    jdbc.update("update posts set updated_at = '2026-01-01 00:00:00' where id = ?", postId);

    call(reader, post(url), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.liked").value(true))
        .andExpect(jsonPath("$.likeCount").value(1));
    call(reader, get("/api/posts/" + postId), null).andExpect(jsonPath("$.liked").value(true));
    call(null, get("/api/posts/" + postId), null)
        .andExpect(jsonPath("$.liked").value(false))
        .andExpect(jsonPath("$.likeCount").value(1));
    assertThat(notifications(owner, "POST_LIKE")).isEqualTo(1);
    // 좋아요·조회수만 바뀌면 글의 "수정됨" 시각은 그대로다
    assertThat(
            jdbc.queryForObject(
                "select updated_at from posts where id = ?", LocalDateTime.class, postId))
        .isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));

    call(reader, post(url), null)
        .andExpect(jsonPath("$.liked").value(false))
        .andExpect(jsonPath("$.likeCount").value(0));
    assertThat(count("select like_count from posts where id = ?", postId)).isZero();
    assertThat(count("select count(*) from post_likes where post_id = ?", postId)).isZero();

    call(null, post(url), null).andExpect(status().isUnauthorized());
    call(newAdmin(), UserRole.ADMIN, post(url), null).andExpect(status().isForbidden());
  }

  @Test
  void cannotLikePrivatePostFromOutside() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PRIVATE");
    long postId = writePost(owner, blogId, "비밀 글");

    call(newUser(), post("/api/posts/" + postId + "/like"), null).andExpect(status().isForbidden());
  }

  // ---------- 카테고리 관리 (T066) ----------

  @Test
  void ownerManagesCategories() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(member, blogId);
    String url = "/api/blogs/" + blogId + "/categories";

    long travel = categoryId(call(owner, post(url), nameBody("여행")));
    long food = categoryId(call(owner, post(url), nameBody("맛집")));
    call(owner, post(url), nameBody("여행")).andExpect(status().isConflict());
    call(owner, post(url), nameBody("가".repeat(21))).andExpect(status().isBadRequest());
    call(member, post(url), nameBody("멤버")).andExpect(status().isForbidden());

    call(owner, put(url + "/" + travel), nameBody("국내 여행")).andExpect(status().isOk());
    call(owner, put(url + "/" + food), nameBody("국내 여행")).andExpect(status().isConflict());
    call(owner, put(url), jsonMapper.writeValueAsString(Map.of("ids", List.of(food, travel))))
        .andExpect(status().isNoContent());
    call(owner, put(url), jsonMapper.writeValueAsString(Map.of("ids", List.of(food))))
        .andExpect(status().isConflict());
    call(null, get(url), null)
        .andExpect(jsonPath("$[0].name").value("맛집"))
        .andExpect(jsonPath("$[1].name").value("국내 여행"));

    Map<String, Object> body = new LinkedHashMap<>(postMap("여행 글"));
    body.put("categoryId", travel);
    long postId =
        body(call(
                    owner,
                    post("/api/blogs/" + blogId + "/posts"),
                    jsonMapper.writeValueAsString(body))
                .andExpect(status().isCreated()))
            .get("id")
            .asLong();

    call(member, delete(url + "/" + travel), null).andExpect(status().isForbidden());
    call(owner, delete(url + "/" + travel), null).andExpect(status().isNoContent());
    call(null, get("/api/posts/" + postId), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.categoryId").doesNotExist());
    call(null, get(url), null).andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void cannotTouchAnotherBlogsCategory() throws Exception {
    User owner = newUser();
    long mine = createBlog(owner, "PUBLIC");
    long theirs = createBlog(newUser(), "PUBLIC");
    long id = categoryId(call(owner, post("/api/blogs/" + mine + "/categories"), nameBody("내 것")));

    call(owner, delete("/api/blogs/" + theirs + "/categories/" + id), null)
        .andExpect(status().isForbidden());
    call(owner, delete("/api/blogs/" + mine + "/categories/" + id), null)
        .andExpect(status().isNoContent());
  }

  // ---------- 도우미 ----------

  private long comment(User user, long postId, String content, Long parentId) throws Exception {
    return body(call(
                user, post("/api/posts/" + postId + "/comments"), commentBody(content, parentId))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private String commentBody(String content, Long parentId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("content", content);
    body.put("parentId", parentId);
    return jsonMapper.writeValueAsString(body);
  }

  private String nameBody(String name) {
    return jsonMapper.writeValueAsString(Map.of("name", name));
  }

  private long categoryId(ResultActions result) throws Exception {
    return body(result.andExpect(status().isCreated())).get("id").asLong();
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private int notifications(User receiver, String type) {
    return count(
        "select count(*) from notifications where receiver_id = ? and type = ?",
        receiver.getId(),
        type);
  }

  private long writePost(User user, long blogId, String title) throws Exception {
    return body(call(
                user,
                post("/api/blogs/" + blogId + "/posts"),
                jsonMapper.writeValueAsString(postMap(title)))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private Map<String, Object> postMap(String title) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", title);
    body.put("content", "본문");
    body.put("tags", new ArrayList<>());
    body.put("notice", false);
    return body;
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

  /** AccountGuard는 DB의 역할을 보므로 DB에도 ADMIN으로 둔다 */
  private User newAdmin() {
    User admin = newUser();
    jdbc.update("update users set role = 'ADMIN' where id = ?", admin.getId());
    return admin;
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "c" + unique + "@example.com",
            "hash",
            "이름",
            "c" + unique,
            "01012345678",
            LocalDateTime.now()));
  }
}
