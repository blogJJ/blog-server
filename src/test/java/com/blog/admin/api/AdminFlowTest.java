package com.blog.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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

/** 메인 관리자 기능을 실제 API로 확인한다 (T115~T126, ADM-01~08, BRD-10). */
@IntegrationTest
class AdminFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;

  @Test
  void threeOwnerWarningsDemoteAndHandTheBlogToTheEarliestManager() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    User early = newUser();
    User late = newUser();
    long blogId = createBlog(owner);
    join(late, blogId);
    join(early, blogId);
    call(owner, put(managers(blogId) + late.getId()), managerBody("EDIT_INFO"))
        .andExpect(status().isNoContent());
    call(owner, put(managers(blogId) + early.getId()), managerBody("MANAGE_POSTS"))
        .andExpect(status().isNoContent());
    // 체크박스를 나중에 받았어도 부블로그장이 먼저 된 사람이 이어받는다 (manager_since)
    jdbc.update(
        "update blog_members set manager_since = ? where blog_id = ? and user_id = ?",
        LocalDateTime.now().minusDays(10),
        blogId,
        early.getId());

    for (int i = 1; i <= 2; i++) {
      call(admin, post(adminBlogs(blogId) + "/owner/warn"), reason("광고 " + i))
          .andExpect(status().isNoContent());
    }
    assertThat(role(blogId, owner)).isEqualTo("OWNER");
    long reportId = reportId(newUser(), "BLOG", blogId, blogId);
    resolve(admin, reportId, "WARN", null, "광고 3").andExpect(status().isNoContent());

    assertThat(role(blogId, owner)).isEqualTo("MEMBER");
    assertThat(role(blogId, early)).isEqualTo("OWNER");
    assertThat(role(blogId, late)).isEqualTo("MANAGER");
    assertThat(jdbc.queryForObject("select owner_id from blogs where id = ?", Long.class, blogId))
        .isEqualTo(early.getId());
    assertThat(
            jdbc.queryForObject("select status from reports where id = ?", String.class, reportId))
        .isEqualTo("OWNER_DEMOTED");
    assertThat(notifications(owner, "OWNER_SANCTION")).isEqualTo(4);
    assertThat(notifications(early, "OWNER_TRANSFER_RESULT")).isEqualTo(1);
    assertThat(logs(admin, "OWNER_WARN")).isEqualTo(3);
    assertThat(logs(admin, "OWNER_DEMOTE")).isEqualTo(1);
    assertThat(logs(admin, "RESOLVE_REPORT")).isEqualTo(1);

    // 새 블로그장은 관리 기능을 쓴다
    call(early, get("/api/blogs/" + blogId + "/members"), null).andExpect(status().isOk());
    call(owner, get("/api/blogs/" + blogId + "/members"), null).andExpect(status().isForbidden());
  }

  @Test
  void demotingTheOnlyOwnerSchedulesTheBlogToClose() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    long blogId = createBlog(owner);

    call(admin, post(adminBlogs(blogId) + "/owner/demote"), reason(""))
        .andExpect(status().isBadRequest());
    call(admin, post(adminBlogs(blogId) + "/owner/demote"), reason("반복 위반"))
        .andExpect(status().isNoContent());

    Map<String, Object> blog =
        jdbc.queryForMap("select status, close_reason from blogs where id = ?", blogId);
    assertThat(blog)
        .containsEntry("status", "CLOSING")
        .containsEntry("close_reason", "OWNER_DEMOTED");
    assertThat(role(blogId, owner)).isEqualTo("MEMBER");
    call(admin, post(adminBlogs(blogId) + "/owner/demote"), reason("또"))
        .andExpect(status().isConflict());
  }

  @Test
  void profileReportSuspendsTheAccountAndTheOwnersBlogShowsANotice() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    User member = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    String slug = slug(blogId);

    long reportId = reportId(reporter, "USER", owner.getId(), null);
    JsonNode list = body(call(admin, get("/api/admin/reports"), null).andExpect(status().isOk()));
    JsonNode row = find(list.get("items"), reportId);
    assertThat(row.get("kind").asString()).isEqualTo("PROFILE");
    assertThat(row.get("resolutions").toString()).contains("ACCOUNT_SUSPEND");

    resolve(admin, reportId, "OWNER_DEMOTE", null, "안 됨").andExpect(status().isBadRequest());
    resolve(admin, reportId, "ACCOUNT_SUSPEND", 14, "욕설 닉네임").andExpect(status().isNoContent());
    resolve(admin, reportId, "NO_ISSUE", null, null).andExpect(status().isConflict());

    call(owner, post("/api/blogs/" + blogId + "/posts"), postBody("정지 중 글"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    call(member, get("/api/blogs/by-slug/" + slug), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ownerNotice").value(containsString("블로그장 정지 중")));
    call(owner, get("/api/blogs/" + blogId + "/members"), null).andExpect(status().isForbidden());
    assertThat(notifications(owner, "ACCOUNT_SANCTION")).isEqualTo(1);
    assertThat(notifications(reporter, "REPORT_RESULT")).isEqualTo(1);

    // 정지 중 들어온 블로그 안 신고는 부블로그장이 없으니 관리자가 블로그장 대신 처리한다 (D-100)
    long memberPost = writePost(member, blogId, "문제 글");
    long inBlog = reportId(reporter, "POST", memberPost, blogId);
    JsonNode inBlogRow =
        find(body(call(admin, get("/api/admin/reports"), null)).get("items"), inBlog);
    assertThat(inBlogRow.get("kind").asString()).isEqualTo("IN_BLOG");
    resolve(admin, inBlog, "SUSPEND", 3, "도배").andExpect(status().isNoContent());
    assertThat(notifications(member, "MEMBER_SANCTION")).isEqualTo(1);

    call(admin, post("/api/admin/users/" + owner.getId() + "/release"), null)
        .andExpect(status().isNoContent());
    call(member, get("/api/blogs/by-slug/" + slug), null)
        .andExpect(jsonPath("$.ownerNotice").doesNotExist());
    call(admin, post("/api/admin/users/" + owner.getId() + "/release"), null)
        .andExpect(status().isConflict());
  }

  @Test
  void permanentSuspensionDemotesTheOwnerEverywhere() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    User manager = newUser();
    long first = createBlog(owner);
    long second = createBlog(owner);
    join(manager, first);
    call(owner, put(managers(first) + manager.getId()), managerBody("EDIT_INFO"))
        .andExpect(status().isNoContent());

    long reportId = reportId(newUser(), "USER", owner.getId(), null);
    resolve(admin, reportId, "ACCOUNT_SUSPEND", null, "영구 정지").andExpect(status().isNoContent());

    assertThat(role(first, manager)).isEqualTo("OWNER");
    assertThat(jdbc.queryForObject("select status from blogs where id = ?", String.class, second))
        .isEqualTo("CLOSING");
    assertThat(
            jdbc.queryForObject(
                "select year(suspended_until) from users where id = ?",
                Integer.class,
                owner.getId()))
        .isEqualTo(9999);
  }

  @Test
  void profileResetRenamesTheMemberAndClearsTheBio() throws Exception {
    User admin = newAdmin();
    User target = newUser();
    jdbc.update("update users set bio = '홍보 링크' where id = ?", target.getId());
    long reportId = reportId(newUser(), "USER", target.getId(), null);

    resolve(admin, reportId, "PROFILE_RESET", null, "부적절한 닉네임").andExpect(status().isNoContent());
    Map<String, Object> row =
        jdbc.queryForMap("select nickname, bio from users where id = ?", target.getId());
    assertThat(row).containsEntry("nickname", "회원" + target.getId());
    assertThat(row.get("bio")).isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_sanctions where user_id = ? and type = 'PROFILE_RESET'",
                Integer.class,
                target.getId()))
        .isEqualTo(1);
  }

  @Test
  void onlyAdminsOpenTheAdminApiAndPages() throws Exception {
    User user = newUser();
    call(user, get("/api/admin/users"), null).andExpect(status().isForbidden());
    call(null, get("/api/admin/users"), null).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/admin/"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login.html?next=/admin/"));
    call(user, get("/admin/js/admin.js"), null).andExpect(status().isForbidden());
    call(newAdmin(), get("/admin/js/admin.js"), null).andExpect(status().isOk());
  }

  @Test
  void contactsAreMaskedUntilRevealAndRevealIsLogged() throws Exception {
    User admin = newAdmin();
    User target = newUser();

    JsonNode list =
        body(
            call(admin, get("/api/admin/users?q=" + target.getNickname()), null)
                .andExpect(status().isOk()));
    JsonNode row = list.get("items").get(0);
    assertThat(row.get("email").asString()).contains("***").doesNotContain(target.getEmail());
    assertThat(row.get("phone").asString()).contains("*");

    call(admin, post("/api/admin/users/" + target.getId() + "/reveal"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(target.getEmail()))
        .andExpect(jsonPath("$.phone").value(target.getPhone()));
    assertThat(logs(admin, "VIEW_PRIVATE_INFO")).isEqualTo(1);
    call(admin, get("/api/admin/logs"), null)
        .andExpect(jsonPath("$.items[0].action").value("VIEW_PRIVATE_INFO"));
  }

  @Test
  void adminHidesDeletesAndRestoresPostsAndComments() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    long blogId = createBlog(owner);
    long postId = writePost(owner, blogId, "숨길 글");

    call(admin, post("/api/admin/posts/" + postId + "/hide"), null)
        .andExpect(status().isNoContent());
    call(owner, get("/api/posts/" + postId), null).andExpect(status().isNotFound());
    call(admin, post("/api/admin/posts/" + postId + "/restore"), null)
        .andExpect(status().isNoContent());
    call(admin, post("/api/admin/posts/" + postId + "/delete"), reason("규정 위반"))
        .andExpect(status().isNoContent());
    assertThat(notifications(owner, "POST_DELETED")).isEqualTo(1);
    assertThat(postCount(blogId)).isZero();

    jdbc.update(
        "update posts set deleted_at = ? where id = ?", LocalDateTime.now().minusDays(31), postId);
    call(admin, post("/api/admin/posts/" + postId + "/restore"), null)
        .andExpect(status().isConflict());
    jdbc.update(
        "update posts set deleted_at = ? where id = ?", LocalDateTime.now().minusDays(29), postId);
    call(admin, post("/api/admin/posts/" + postId + "/restore"), null)
        .andExpect(status().isNoContent());
    assertThat(postCount(blogId)).isEqualTo(1);

    User commenter = newUser();
    long commentId =
        body(call(commenter, post("/api/posts/" + postId + "/comments"), "{\"content\":\"광고 댓글\"}")
                .andExpect(status().isCreated()))
            .get("id")
            .asLong();
    call(admin, post("/api/admin/comments/" + commentId + "/delete"), null)
        .andExpect(status().isNoContent());
    assertThat(notifications(commenter, "POST_DELETED")).isEqualTo(1);
    call(admin, get("/api/admin/posts/" + postId + "/comments"), null)
        .andExpect(jsonPath("$[0].status").value("DELETED"));
  }

  @Test
  void hiddenBlogDisappearsAndForcedCloseWaitsSevenDays() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    long blogId = createBlog(owner);
    String slug = slug(blogId);

    call(admin, post(adminBlogs(blogId) + "/hide"), null).andExpect(status().isNoContent());
    call(owner, get("/api/blogs/by-slug/" + slug), null).andExpect(status().isNotFound());
    call(admin, post(adminBlogs(blogId) + "/unhide"), null).andExpect(status().isNoContent());
    call(owner, get("/api/blogs/by-slug/" + slug), null).andExpect(status().isOk());

    call(admin, post(adminBlogs(blogId) + "/close"), reason("불법 정보"))
        .andExpect(status().isNoContent());
    Map<String, Object> blog =
        jdbc.queryForMap(
            "select status, close_reason, close_scheduled_at from blogs where id = ?", blogId);
    assertThat(blog).containsEntry("status", "CLOSING").containsEntry("close_reason", "ADMIN");
    assertThat(blog.get("close_scheduled_at").toString()).contains("04:00");

    call(admin, get("/api/admin/blogs?q=" + slug), null)
        .andExpect(jsonPath("$.items[0].status").value("CLOSING"))
        .andExpect(jsonPath("$.items[0].ownerNickname").value(owner.getNickname()));
  }

  @Test
  void noticeGoesToEveryMemberAndIsReadableByAnyone() throws Exception {
    User admin = newAdmin();
    User member = newUser();
    long id =
        body(call(
                    admin,
                    post("/api/admin/notices"),
                    jsonMapper.writeValueAsString(Map.of("title", "점검 안내", "content", "**새벽** 점검")))
                .andExpect(status().isCreated()))
            .get("id")
            .asLong();

    assertThat(notifications(member, "NOTICE")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select b.slug from posts p join blogs b on b.id = p.blog_id where p.id = ?",
                String.class,
                id))
        .isEqualTo("main");
    call(null, get("/api/notices/" + id), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.html").value(containsString("<strong>새벽</strong>")));
    call(null, get("/api/blogs/by-slug/main"), null).andExpect(status().isNotFound());
    call(member, get("/api/notifications"), null)
        .andExpect(jsonPath("$.items[0].link").value("/notices.html?id=" + id));
    call(admin, post("/api/admin/notices"), "{\"title\":\"\",\"content\":\"x\"}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void statsCountMembersBlogsAndDailyActivity() throws Exception {
    User admin = newAdmin();
    createBlog(newUser());
    call(admin, get("/api/admin/stats?days=7"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.daily.length()").value(7))
        .andExpect(jsonPath("$.userCount").isNumber())
        .andExpect(jsonPath("$.daily[6].signups").value(org.hamcrest.Matchers.greaterThan(0)));
  }

  // ---------- 도우미 ----------

  private static String managers(long blogId) {
    return "/api/blogs/" + blogId + "/managers/";
  }

  private static String adminBlogs(long blogId) {
    return "/api/admin/blogs/" + blogId;
  }

  private String reason(String reason) {
    return jsonMapper.writeValueAsString(Map.of("reason", reason));
  }

  private String managerBody(String... permissions) {
    return jsonMapper.writeValueAsString(
        Map.of("manager", true, "permissions", List.of(permissions)));
  }

  private ResultActions resolve(
      User admin, long reportId, String resolution, Integer days, String reason) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("resolution", resolution);
    body.put("days", days);
    body.put("reason", reason);
    return call(
        admin,
        post("/api/admin/reports/" + reportId + "/resolve"),
        jsonMapper.writeValueAsString(body));
  }

  private long reportId(User reporter, String targetType, long targetId, Long blogId)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetType", targetType);
    body.put("targetId", targetId);
    body.put("blogId", blogId);
    body.put("reason", "SPAM");
    JsonNode created =
        body(
            call(reporter, post("/api/reports"), jsonMapper.writeValueAsString(body))
                .andExpect(status().isCreated()));
    assertThat(created.get("handlerScope").asString()).isEqualTo("ADMIN");
    return created.get("id").asLong();
  }

  private static JsonNode find(JsonNode items, long id) {
    for (JsonNode item : items) {
      if (item.get("id").asLong() == id) {
        return item;
      }
    }
    throw new AssertionError("report " + id + " not in list");
  }

  private String role(long blogId, User user) {
    return jdbc.queryForObject(
        "select role from blog_members where blog_id = ? and user_id = ?",
        String.class,
        blogId,
        user.getId());
  }

  private String slug(long blogId) {
    return jdbc.queryForObject("select slug from blogs where id = ?", String.class, blogId);
  }

  private int postCount(long blogId) {
    return jdbc.queryForObject("select post_count from blogs where id = ?", Integer.class, blogId);
  }

  private int notifications(User receiver, String type) {
    return jdbc.queryForObject(
        "select count(*) from notifications where receiver_id = ? and type = ?",
        Integer.class,
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
    body.put("slug", "a-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "관리자 시험");
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

  private String postBody(String title) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", title);
    body.put("content", "본문");
    body.put("tags", List.of());
    body.put("notice", false);
    return jsonMapper.writeValueAsString(body);
  }

  private long writePost(User user, long blogId, String title) throws Exception {
    return body(call(user, post("/api/blogs/" + blogId + "/posts"), postBody(title))
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
            "a" + unique + "@example.com",
            "hash",
            "이름",
            "a" + unique,
            "010" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000),
            LocalDateTime.now()));
  }
}
