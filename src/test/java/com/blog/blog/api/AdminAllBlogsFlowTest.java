package com.blog.blog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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

/** 메인 관리자는 멤버가 아니어도 모든 블로그를 블로그장처럼 관리하고, 위임만 못 한다 (D-122). */
@IntegrationTest
class AdminAllBlogsFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;

  @Test
  void adminManagesAnyBlogWithoutBecomingAMember() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    User applicant = newUser();
    User other = newUser();
    String slug = slug();
    long blogId = createBlog(owner, slug, "PUBLIC", "APPROVAL");

    // 블로그 화면: 관리 버튼은 보이고 위임은 안 보인다
    call(admin, get("/api/blogs/by-slug/" + slug), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewer.role").doesNotExist())
        .andExpect(jsonPath("$.viewer.canEditInfo").value(true))
        .andExpect(jsonPath("$.viewer.canManageMembers").value(true))
        .andExpect(jsonPath("$.viewer.canManagePosts").value(true))
        .andExpect(jsonPath("$.viewer.owner").value(true))
        .andExpect(jsonPath("$.viewer.canTransfer").value(false));
    call(owner, get("/api/blogs/by-slug/" + slug), null)
        .andExpect(jsonPath("$.viewer.owner").value(true))
        .andExpect(jsonPath("$.viewer.canTransfer").value(true));

    // 정보 수정·카테고리
    call(admin, put("/api/blogs/" + blogId), infoBody("관리자가 고친 이름", "PUBLIC", "APPROVAL"))
        .andExpect(status().isOk());
    assertThat(jdbc.queryForObject("select name from blogs where id = ?", String.class, blogId))
        .isEqualTo("관리자가 고친 이름");
    call(admin, post("/api/blogs/" + blogId + "/categories"), "{\"name\":\"잡담\"}")
        .andExpect(status().isCreated());

    // 참여 승인
    call(applicant, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
    JsonNode requests =
        body(
            call(admin, get("/api/blogs/" + blogId + "/join-requests"), null)
                .andExpect(status().isOk()));
    long requestId = requests.get(0).get("id").asLong();
    call(admin, post("/api/blogs/" + blogId + "/join-requests/" + requestId + "/approve"), null)
        .andExpect(status().isNoContent());
    call(other, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
    long otherRequest =
        body(call(admin, get("/api/blogs/" + blogId + "/join-requests"), null))
            .get(0)
            .get("id")
            .asLong();
    call(admin, post("/api/blogs/" + blogId + "/join-requests/" + otherRequest + "/approve"), null)
        .andExpect(status().isNoContent());

    // 멤버 제재: 멤버·부블로그장은 되고 블로그장은 안 된다
    call(admin, post(members(blogId) + applicant.getId() + "/suspend"), sanction("도배", 3))
        .andExpect(status().isNoContent());
    call(admin, post(members(blogId) + applicant.getId() + "/release"), null)
        .andExpect(status().isNoContent());
    call(admin, put("/api/blogs/" + blogId + "/managers/" + other.getId()), managerBody(true))
        .andExpect(status().isNoContent());
    assertThat(role(blogId, other)).isEqualTo("MANAGER");
    call(admin, post(members(blogId) + other.getId() + "/warn"), sanction("욕설", null))
        .andExpect(status().isNoContent());
    call(admin, post(members(blogId) + owner.getId() + "/warn"), sanction("욕설", null))
        .andExpect(status().isForbidden());
    call(admin, get("/api/blogs/" + blogId + "/members"), null).andExpect(status().isOk());
    call(admin, get("/api/blogs/" + blogId + "/blacklist"), null).andExpect(status().isOk());

    // 위임은 진짜 블로그장만
    call(admin, post("/api/blogs/" + blogId + "/transfers"), "{\"toUserId\":" + other.getId() + "}")
        .andExpect(status().isForbidden());
    call(admin, get("/api/blogs/" + blogId + "/transfers/pending"), null)
        .andExpect(status().isForbidden());

    // 관리자는 멤버가 되지 않고, 블로그장은 그대로
    assertThat(role(blogId, owner)).isEqualTo("OWNER");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from blog_members where user_id = ?",
                Integer.class,
                admin.getId()))
        .isZero();
  }

  @Test
  void adminHandlesInBlogReports() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    User member = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner, slug(), "PUBLIC", "OPEN");
    join(member, blogId);
    long postId = writePost(member, blogId);
    call(reporter, post("/api/reports"), report("POST", postId, blogId))
        .andExpect(jsonPath("$.handlerScope").value("BLOG_OWNER"));

    JsonNode pending =
        body(
            call(admin, get("/api/blogs/" + blogId + "/reports"), null).andExpect(status().isOk()));
    long reportId = pending.get(0).get("id").asLong();
    call(
            admin,
            post("/api/blogs/" + blogId + "/reports/" + reportId + "/resolve"),
            "{\"resolution\":\"WARN\",\"reason\":\"광고 글\"}")
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject("select status from reports where id = ?", String.class, reportId))
        .isEqualTo("WARNED");
  }

  @Test
  void adminClosesAndRevokesFromTheBlogPage() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    long blogId = createBlog(owner, slug(), "PUBLIC", "OPEN");

    call(admin, post("/api/blogs/" + blogId + "/close"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSING"));
    assertThat(
            jdbc.queryForObject(
                "select close_reason from blogs where id = ?", String.class, blogId))
        .isEqualTo("ADMIN");
    assertThat(actionLogs(admin, "CLOSE_BLOG")).isEqualTo(1);

    call(admin, post("/api/blogs/" + blogId + "/close/revoke"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    assertThat(actionLogs(admin, "REVOKE_CLOSE_BLOG")).isEqualTo(1);

    // 블로그장이 폐쇄한 것도 관리자가 철회한다
    call(owner, post("/api/blogs/" + blogId + "/close"), null).andExpect(status().isOk());
    call(admin, post("/api/blogs/" + blogId + "/close/revoke"), null)
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void adminReadsPrivateBlogsAndSubscribesButStillCannotWriteOrJoin() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    User outsider = newUser();
    String slug = slug();
    long blogId = createBlog(owner, slug, "PRIVATE", "OPEN");
    long postId = writePost(owner, blogId);

    call(outsider, get("/api/posts/" + postId), null).andExpect(status().isForbidden());
    call(admin, get("/api/blogs/by-slug/" + slug), null).andExpect(status().isOk());
    call(admin, get("/api/blogs/" + blogId + "/posts"), null).andExpect(status().isOk());
    call(admin, get("/api/posts/" + postId), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canEdit").value(false))
        .andExpect(jsonPath("$.canDelete").value(true));

    call(admin, post("/api/blogs/" + blogId + "/posts"), postBody())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ADMIN_NOT_ALLOWED"));
    call(admin, post("/api/blogs/" + blogId + "/join"), null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ADMIN_NOT_ALLOWED"));

    // 일부 공개는 공유 링크 없이도 보고, 링크를 새로 만든다
    User linkOwner = newUser();
    String linkSlug = slug();
    long linkBlog = createBlog(linkOwner, linkSlug, "LINK_ONLY", "OPEN");
    call(outsider, get("/api/blogs/by-slug/" + linkSlug), null).andExpect(status().isForbidden());
    call(admin, get("/api/blogs/by-slug/" + linkSlug), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shareKey").exists());
    call(admin, post("/api/blogs/" + linkBlog + "/share-link"), null).andExpect(status().isOk());

    call(admin, post("/api/blogs/" + blogId + "/subscribe"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subscribed").value(true));
    // 구독 탭에도 비공개 블로그 글이 나온다
    JsonNode feed = body(call(admin, get("/api/feed?tab=subscribed"), null));
    assertThat(feed.get("items").valueStream().map(n -> n.get("id").asLong()).toList())
        .containsExactly(postId);
    call(admin, post("/api/users/" + owner.getId() + "/follow"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(true));
  }

  @Test
  void nonMemberStillHasNoManagementRights() throws Exception {
    User owner = newUser();
    User outsider = newUser();
    String slug = slug();
    long blogId = createBlog(owner, slug, "PUBLIC", "OPEN");

    call(outsider, get("/api/blogs/by-slug/" + slug), null)
        .andExpect(jsonPath("$.viewer.canEditInfo").value(false))
        .andExpect(jsonPath("$.viewer.canManageMembers").value(false))
        .andExpect(jsonPath("$.viewer.owner").value(false))
        .andExpect(jsonPath("$.viewer.canTransfer").value(false))
        .andExpect(jsonPath("$.shareKey").doesNotExist());
    call(outsider, put("/api/blogs/" + blogId), infoBody("남의 블로그", "PUBLIC", "OPEN"))
        .andExpect(status().isForbidden());
    call(outsider, get("/api/blogs/" + blogId + "/join-requests"), null)
        .andExpect(status().isForbidden());
    call(outsider, get("/api/blogs/" + blogId + "/members"), null)
        .andExpect(status().isForbidden());
    call(outsider, get("/api/blogs/" + blogId + "/reports"), null)
        .andExpect(status().isForbidden());
    call(outsider, post("/api/blogs/" + blogId + "/categories"), "{\"name\":\"잡담\"}")
        .andExpect(status().isForbidden());
    call(outsider, post("/api/blogs/" + blogId + "/close"), null).andExpect(status().isForbidden());
    call(outsider, get("/api/blogs/" + blogId + "/transfers/pending"), null)
        .andExpect(status().isForbidden());
  }

  // ---------- 도우미 ----------

  private static String members(long blogId) {
    return "/api/blogs/" + blogId + "/members/";
  }

  private static String slug() {
    return "a-" + UUID.randomUUID().toString().substring(0, 12);
  }

  private String role(long blogId, User user) {
    return jdbc.queryForObject(
        "select role from blog_members where blog_id = ? and user_id = ?",
        String.class,
        blogId,
        user.getId());
  }

  private int actionLogs(User admin, String action) {
    return jdbc.queryForObject(
        "select count(*) from admin_action_logs where admin_id = ? and action = ?",
        Integer.class,
        admin.getId(),
        action);
  }

  private String infoBody(String name, String visibility, String joinPolicy) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("description", "");
    body.put("visibility", visibility);
    body.put("joinPolicy", joinPolicy);
    body.put("tags", List.of());
    return jsonMapper.writeValueAsString(body);
  }

  private String sanction(String reason, Integer days) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", reason);
    body.put("days", days);
    return jsonMapper.writeValueAsString(body);
  }

  private String managerBody(boolean manager) {
    return jsonMapper.writeValueAsString(
        Map.of("manager", manager, "permissions", List.of("MANAGE_POSTS")));
  }

  private String report(String targetType, long targetId, long blogId) {
    return jsonMapper.writeValueAsString(
        Map.of("targetType", targetType, "targetId", targetId, "blogId", blogId, "reason", "SPAM"));
  }

  private String postBody() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "글");
    body.put("content", "본문");
    body.put("tags", List.of());
    body.put("notice", false);
    return jsonMapper.writeValueAsString(body);
  }

  private ResultActions call(User user, MockHttpServletRequestBuilder request, String json)
      throws Exception {
    request.with(csrf());
    if (user != null) {
      UserRole role = user.getRole();
      request.with(
          authentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  new AuthUser(user.getId(), role),
                  null,
                  List.of(new SimpleGrantedAuthority("ROLE_" + role.name())))));
    }
    if (json != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mockMvc.perform(request);
  }

  private JsonNode body(ResultActions result) throws Exception {
    return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private long createBlog(User owner, String slug, String visibility, String joinPolicy)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", slug);
    body.put("name", "관리자 시험");
    body.put("visibility", visibility);
    body.put("joinPolicy", joinPolicy);
    body.put("tags", List.of());
    return body(call(owner, post("/api/blogs"), jsonMapper.writeValueAsString(body))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private void join(User user, long blogId) throws Exception {
    call(user, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
  }

  private long writePost(User user, long blogId) throws Exception {
    return body(call(user, post("/api/blogs/" + blogId + "/posts"), postBody())
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
