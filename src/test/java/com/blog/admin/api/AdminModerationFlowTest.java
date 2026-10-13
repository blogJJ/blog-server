package com.blog.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.DisplayName;
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

/** 관리자 글·댓글 관리와 회원 조회를 실제 API로 확인한다 (T117, T119, ADM-01, ADM-03, ADM-06, 4.4, 4.5). */
@IntegrationTest
class AdminModerationFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;

  // ---------- 글·댓글 (ADM-03) ----------

  @Test
  @DisplayName("ADM-03: 일반 회원은 글·댓글을 숨기거나 지울 수 없다(403)")
  void onlyAdminsModerateContent() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner);
    long postId = writePost(owner, blogId, "글");
    long commentId = writeComment(newUser(), postId, "댓글");

    call(owner, post("/api/admin/posts/" + postId + "/hide"), null)
        .andExpect(status().isForbidden());
    call(owner, post("/api/admin/comments/" + commentId + "/delete"), null)
        .andExpect(status().isForbidden());
    assertThat(postStatus(postId)).isEqualTo("PUBLISHED");
    assertThat(commentStatus(commentId)).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("ADM-03: 숨긴 글을 지우면 글 수를 두 번 빼지 않고, 이미 지운 글·숨긴 글을 다시 숨기거나 지울 수 없다")
  void hiddenThenDeletedPostIsCountedOnce() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    long blogId = createBlog(owner);
    long postId = writePost(owner, blogId, "숨겼다 지울 글");
    writePost(owner, blogId, "남을 글");
    assertThat(postCount(blogId)).isEqualTo(2);

    call(admin, post("/api/admin/posts/" + postId + "/hide"), reason("광고"))
        .andExpect(status().isNoContent());
    assertThat(postCount(blogId)).isEqualTo(1);
    call(admin, post("/api/admin/posts/" + postId + "/hide"), null)
        .andExpect(status().isConflict());

    call(admin, post("/api/admin/posts/" + postId + "/delete"), reason("  규정 위반  "))
        .andExpect(status().isNoContent());
    assertThat(postCount(blogId)).isEqualTo(1);
    assertThat(postStatus(postId)).isEqualTo("DELETED");
    assertThat(lastNotification(owner, "POST_DELETED"))
        .isEqualTo("관리자가 내 글 '숨겼다 지울 글'을(를) 삭제했어요. 사유: 규정 위반");
    call(admin, post("/api/admin/posts/" + postId + "/delete"), null)
        .andExpect(status().isConflict());
    assertThat(notifications(owner, "POST_DELETED")).isEqualTo(1);
    assertThat(logs(admin, "HIDE_POST")).isEqualTo(1);
    assertThat(logs(admin, "DELETE_POST")).isEqualTo(1);
  }

  @Test
  @DisplayName("ADM-03·4.5: 게시 중인 글이나 폐쇄된 블로그의 글은 복구할 수 없다")
  void restoreRejectsPublishedPostsAndClosedBlogs() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    long blogId = createBlog(owner);
    long postId = writePost(owner, blogId, "글");

    call(admin, post("/api/admin/posts/" + postId + "/restore"), null)
        .andExpect(status().isConflict());

    call(admin, post("/api/admin/posts/" + postId + "/delete"), null)
        .andExpect(status().isNoContent());
    // 사유가 없으면 알림에 사유를 붙이지 않는다
    assertThat(lastNotification(owner, "POST_DELETED")).isEqualTo("관리자가 내 글 '글'을(를) 삭제했어요.");
    jdbc.update("update blogs set status = 'CLOSED' where id = ?", blogId);
    call(admin, post("/api/admin/posts/" + postId + "/restore"), null)
        .andExpect(status().isConflict());
    assertThat(postStatus(postId)).isEqualTo("DELETED");
    assertThat(logs(admin, "RESTORE_POST")).isZero();
  }

  @Test
  @DisplayName("ADM-03: 숨긴 댓글은 댓글 수에서 빠지고, 숨긴 댓글을 지워도 다시 빼지 않으며 작성자에게 앞부분과 사유를 알린다")
  void hiddenThenDeletedCommentIsCountedOnceAndNotifiesTheAuthor() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    User commenter = newUser();
    long blogId = createBlog(owner);
    long postId = writePost(owner, blogId, "글");
    String longText = "가나다라마바사아자차카타파하가나다라마바사아자차";
    long commentId = writeComment(commenter, postId, longText);
    writeComment(commenter, postId, "남을 댓글");
    assertThat(commentCount(postId)).isEqualTo(2);

    call(admin, post("/api/admin/comments/" + commentId + "/hide"), null)
        .andExpect(status().isNoContent());
    assertThat(commentCount(postId)).isEqualTo(1);
    call(admin, post("/api/admin/comments/" + commentId + "/hide"), null)
        .andExpect(status().isConflict());
    call(admin, get("/api/admin/posts/" + postId + "/comments"), null)
        .andExpect(jsonPath("$[0].id").value(commentId))
        .andExpect(jsonPath("$[0].status").value("HIDDEN"))
        .andExpect(jsonPath("$[1].status").value("ACTIVE"));

    call(admin, post("/api/admin/comments/" + commentId + "/delete"), reason("광고"))
        .andExpect(status().isNoContent());
    assertThat(commentCount(postId)).isEqualTo(1);
    assertThat(lastNotification(commenter, "POST_DELETED"))
        .isEqualTo("관리자가 내 댓글 '" + longText.substring(0, 20) + "…'을(를) 삭제했어요. 사유: 광고");
    call(admin, post("/api/admin/comments/" + commentId + "/delete"), null)
        .andExpect(status().isConflict());
    assertThat(logs(admin, "HIDE_COMMENT")).isEqualTo(1);
    assertThat(logs(admin, "DELETE_COMMENT")).isEqualTo(1);
  }

  @Test
  @DisplayName("ADM-03: 없는 글·댓글은 404")
  void missingContentIsNotFound() throws Exception {
    User admin = newAdmin();
    long missing = Long.MAX_VALUE;
    call(admin, post("/api/admin/posts/" + missing + "/hide"), null)
        .andExpect(status().isNotFound());
    call(admin, post("/api/admin/posts/" + missing + "/restore"), null)
        .andExpect(status().isNotFound());
    call(admin, post("/api/admin/comments/" + missing + "/hide"), null)
        .andExpect(status().isNotFound());
    call(admin, get("/api/admin/posts/" + missing + "/comments"), null)
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("ADM-03: 관리자 글 검색은 제목으로 찾고 숨김·삭제한 글도 상태와 함께 보여 준다")
  void postSearchIncludesHiddenAndDeletedPosts() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    long blogId = createBlog(owner);
    String tag = "검색" + UUID.randomUUID().toString().substring(0, 8);
    long shown = writePost(owner, blogId, tag + " 게시");
    long hidden = writePost(owner, blogId, tag + " 숨김");
    long deleted = writePost(owner, blogId, tag + " 삭제");
    call(admin, post("/api/admin/posts/" + hidden + "/hide"), null)
        .andExpect(status().isNoContent());
    call(admin, post("/api/admin/posts/" + deleted + "/delete"), null)
        .andExpect(status().isNoContent());

    JsonNode page = body(call(admin, get("/api/admin/posts?q=" + tag), null));
    assertThat(page.get("totalElements").asLong()).isEqualTo(3);
    Map<Long, String> statuses = new LinkedHashMap<>();
    for (JsonNode row : page.get("items")) {
      statuses.put(row.get("id").asLong(), row.get("status").asString());
      assertThat(row.get("blogId").asLong()).isEqualTo(blogId);
      assertThat(row.get("authorNickname").asString()).isEqualTo(owner.getNickname());
    }
    assertThat(statuses)
        .containsEntry(shown, "PUBLISHED")
        .containsEntry(hidden, "HIDDEN")
        .containsEntry(deleted, "DELETED");
  }

  // ---------- 회원 (ADM-01, ADM-06) ----------

  @Test
  @DisplayName("ADM-01: 회원 상세는 참여한 블로그와 역할, 계정 제재·블로그장 제재 이력을 보여 주고 연락처는 가린다")
  void userDetailShowsBlogsAndSanctionHistoryWithMaskedContacts() throws Exception {
    User admin = newAdmin();
    User target = newUser();
    User other = newUser();
    long ownBlog = createBlog(target);
    long joined = createBlog(other);
    join(target, joined);
    jdbc.update(
        "update users set bio = '소개', profile_image = 'p.png' where id = ?", target.getId());

    call(admin, post("/api/admin/blogs/" + ownBlog + "/owner/warn"), reason("광고 블로그"))
        .andExpect(status().isNoContent());
    long reportId = profileReport(target);
    call(
            admin,
            post("/api/admin/reports/" + reportId + "/resolve"),
            "{\"resolution\":\"WARN\",\"reason\":\"욕설\"}")
        .andExpect(status().isNoContent());

    JsonNode detail =
        body(
            call(admin, get("/api/admin/users/" + target.getId()), null)
                .andExpect(status().isOk()));
    assertThat(detail.get("user").get("email").asString())
        .contains("*")
        .isNotEqualTo(target.getEmail());
    assertThat(detail.get("user").get("phone").asString()).contains("*");
    assertThat(detail.get("bio").asString()).isEqualTo("소개");
    assertThat(detail.get("profileImage").asString()).endsWith("p.png");

    Map<Long, String> roles = new LinkedHashMap<>();
    for (JsonNode blog : detail.get("blogs")) {
      roles.put(blog.get("blogId").asLong(), blog.get("role").asString());
    }
    assertThat(roles).containsEntry(ownBlog, "OWNER").containsEntry(joined, "MEMBER");

    JsonNode account = detail.get("accountSanctions");
    assertThat(account.size()).isEqualTo(1);
    assertThat(account.get(0).get("type").asString()).isEqualTo("WARNING");
    assertThat(account.get(0).get("reportId").asLong()).isEqualTo(reportId);
    assertThat(account.get(0).get("admin").asString()).isEqualTo(admin.getNickname());

    JsonNode owner = detail.get("ownerSanctions");
    assertThat(owner.size()).isEqualTo(1);
    assertThat(owner.get(0).get("blogId").asLong()).isEqualTo(ownBlog);
    assertThat(owner.get(0).get("type").asString()).isEqualTo("WARNING");
    assertThat(owner.get(0).get("reason").asString()).isEqualTo("광고 블로그");
    assertThat(owner.get(0).get("reportId").isNull()).isTrue();

    // 상세 보기만으로는 개인정보 열람 기록을 남기지 않는다 (ADM-06은 "전체 보기"만)
    assertThat(logs(admin, "VIEW_PRIVATE_INFO")).isZero();
  }

  @Test
  @DisplayName("ADM-01: 탈퇴한 회원도 검색·상세에 나오고 탈퇴 시각을 보여 주며, 없는 회원은 상세·전체 보기 모두 404")
  void withdrawnUsersAreSearchableAndMissingUsersAreNotFound() throws Exception {
    User admin = newAdmin();
    User withdrawn = newUser();
    jdbc.update(
        "update users set status = 'WITHDRAWN', withdrawn_at = now() where id = ?",
        withdrawn.getId());

    call(admin, get("/api/admin/users?q=" + withdrawn.getNickname()), null)
        .andExpect(jsonPath("$.items[0].id").value(withdrawn.getId()))
        .andExpect(jsonPath("$.items[0].status").value("WITHDRAWN"));
    call(admin, get("/api/admin/users/" + withdrawn.getId()), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.withdrawnAt").isNotEmpty())
        .andExpect(jsonPath("$.blogs.length()").value(0));

    call(admin, get("/api/admin/users/" + Long.MAX_VALUE), null).andExpect(status().isNotFound());
    call(admin, post("/api/admin/users/" + Long.MAX_VALUE + "/reveal"), null)
        .andExpect(status().isNotFound());
    assertThat(logs(admin, "VIEW_PRIVATE_INFO")).isZero();
  }

  // ---------- 도우미 ----------

  private String reason(String reason) {
    return jsonMapper.writeValueAsString(Map.of("reason", reason));
  }

  private long profileReport(User target) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetType", "USER");
    body.put("targetId", target.getId());
    body.put("reason", "ABUSE");
    return body(call(newUser(), post("/api/reports"), jsonMapper.writeValueAsString(body))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private String postStatus(long postId) {
    return jdbc.queryForObject("select status from posts where id = ?", String.class, postId);
  }

  private String commentStatus(long commentId) {
    return jdbc.queryForObject("select status from comments where id = ?", String.class, commentId);
  }

  private int postCount(long blogId) {
    return jdbc.queryForObject("select post_count from blogs where id = ?", Integer.class, blogId);
  }

  private int commentCount(long postId) {
    return jdbc.queryForObject(
        "select comment_count from posts where id = ?", Integer.class, postId);
  }

  private int notifications(User receiver, String type) {
    return jdbc.queryForObject(
        "select count(*) from notifications where receiver_id = ? and type = ?",
        Integer.class,
        receiver.getId(),
        type);
  }

  private String lastNotification(User receiver, String type) {
    return jdbc.queryForObject(
        "select message from notifications where receiver_id = ? and type = ?"
            + " order by id desc limit 1",
        String.class,
        receiver.getId(),
        type);
  }

  private int logs(User admin, String action) {
    return jdbc.queryForObject(
        "select count(*) from admin_action_logs where admin_id = ? and action = ?",
        Integer.class,
        admin.getId(),
        action);
  }

  private ResultActions call(User user, MockHttpServletRequestBuilder request, String json)
      throws Exception {
    request.with(csrf());
    if (user != null) {
      String role = user.isAdmin() ? "ADMIN" : "USER";
      request.with(
          authentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  new AuthUser(user.getId(), UserRole.valueOf(role)),
                  null,
                  List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
    }
    if (json != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mockMvc.perform(request);
  }

  private JsonNode body(ResultActions result) throws Exception {
    return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private long createBlog(User owner) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "am-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "관리자 콘텐츠 시험");
    body.put("visibility", "PUBLIC");
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

  private long writePost(User user, long blogId, String title) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", title);
    body.put("content", "본문");
    body.put("tags", List.of());
    body.put("notice", false);
    return body(call(
                user, post("/api/blogs/" + blogId + "/posts"), jsonMapper.writeValueAsString(body))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private long writeComment(User user, long postId, String content) throws Exception {
    return body(call(
                user,
                post("/api/posts/" + postId + "/comments"),
                jsonMapper.writeValueAsString(Map.of("content", content)))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private User newAdmin() {
    User admin = newUser();
    jdbc.update("update users set role = 'ADMIN' where id = ?", admin.getId());
    return userRepository.findById(admin.getId()).orElseThrow();
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "am" + unique + "@example.com",
            "hash",
            "이름",
            "am" + unique,
            "010" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000),
            LocalDateTime.now()));
  }
}
