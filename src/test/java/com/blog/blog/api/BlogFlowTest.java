package com.blog.blog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/**
 * 블로그 만들기·찾기·참여를 실제 API로 확인한다 (T044~T053, BLG-01~06, BLG-10, D-115, SC-006). 테스트마다 새 회원과 새 주소를 쓴다.
 */
@IntegrationTest
class BlogFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;

  // ---------- 만들기 (T044, T045) ----------

  @Test
  void createMakesOwnerAndNormalizesTags() throws Exception {
    User owner = newUser();
    String slug = slug();

    call(
            owner,
            post("/api/blogs"),
            blogBody(slug, "PUBLIC", "OPEN", List.of("#Java", "스프링 부트", "java")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.slug").value(slug));

    assertThat(
            jdbc.queryForObject(
                "select m.role from blog_members m join blogs b on b.id = m.blog_id where b.slug = ?",
                String.class,
                slug))
        .isEqualTo("OWNER");
    call(null, get("/api/blogs/by-slug/" + slug), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tags[0]").value("java"))
        .andExpect(jsonPath("$.tags[1]").value("스프링_부트"))
        .andExpect(jsonPath("$.tags.length()").value(2))
        .andExpect(jsonPath("$.memberCount").value(1))
        .andExpect(jsonPath("$.shareKey").doesNotExist());
  }

  @Test
  void slugRules() throws Exception {
    User owner = newUser();
    for (String bad : List.of("ab", "admin", "Has Space", "-start", "한글주소")) {
      call(owner, post("/api/blogs"), blogBody(bad, "PUBLIC", "OPEN", List.of()))
          .andExpect(status().isBadRequest());
    }
    String slug = slug();
    call(owner, post("/api/blogs"), blogBody(slug, "PRIVATE", "OPEN", List.of()))
        .andExpect(status().isCreated());
    call(newUser(), post("/api/blogs"), blogBody(slug, "PUBLIC", "OPEN", List.of()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("이미 쓰고 있는 주소")));
    call(null, get("/api/blogs/slug-check?slug=" + slug), null)
        .andExpect(jsonPath("$.available").value(false));
  }

  @Test
  void guestCannotCreate() throws Exception {
    mockMvc
        .perform(
            post("/api/blogs")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(blogBody(slug(), "PUBLIC", "OPEN", List.of())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void adminCannotCreate() throws Exception {
    User admin = newUser();
    jdbc.update("update users set role = 'ADMIN' where id = ?", admin.getId());
    call(admin, post("/api/blogs"), blogBody(slug(), "PUBLIC", "OPEN", List.of()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ADMIN_NOT_ALLOWED"));
  }

  @Test
  void publicLimitCountsLinkOnlyAndHoldsUnderConcurrency() throws Exception {
    User owner = newUser();
    call(owner, post("/api/blogs"), blogBody(slug(), "LINK_ONLY", "OPEN", List.of()))
        .andExpect(status().isCreated());

    // 공개 3개 중 1개가 이미 있다. 동시에 5번 만들면 2개만 생겨야 한다 (SC-006)
    ExecutorService pool = Executors.newFixedThreadPool(5);
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      String s = slug();
      tasks.add(
          () ->
              call(owner, post("/api/blogs"), blogBody(s, "PUBLIC", "OPEN", List.of()))
                  .andReturn()
                  .getResponse()
                  .getStatus());
    }
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> f : pool.invokeAll(tasks)) {
      statuses.add(f.get());
    }
    pool.shutdown();

    assertThat(statuses.stream().filter(s -> s == 201)).hasSize(2);
    assertThat(statuses.stream().filter(s -> s == 409)).hasSize(3);
    // 비공개는 따로 센다
    call(owner, post("/api/blogs"), blogBody(slug(), "PRIVATE", "OPEN", List.of()))
        .andExpect(status().isCreated());
  }

  // ---------- 열람 권한·공유 링크 (T046, T047) ----------

  @Test
  void linkOnlyNeedsShareKeyAndRegenerateInvalidatesOld() throws Exception {
    User owner = newUser();
    String slug = slug();
    JsonNode created =
        body(call(owner, post("/api/blogs"), blogBody(slug, "LINK_ONLY", "OPEN", List.of())));
    String key = created.get("shareKey").asString();
    assertThat(key).hasSizeGreaterThanOrEqualTo(40);

    call(null, get("/api/blogs/by-slug/" + slug), null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(containsString("공유 링크")));
    call(null, get("/api/blogs/by-slug/" + slug + "?share=" + key), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewer.loggedIn").value(false));
    call(owner, get("/api/blogs/by-slug/" + slug), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shareKey").value(key));

    long blogId = created.get("id").asLong();
    String newKey =
        body(call(owner, post("/api/blogs/" + blogId + "/share-link"), null))
            .get("shareKey")
            .asString();
    assertThat(newKey).isNotEqualTo(key);
    call(null, get("/api/blogs/by-slug/" + slug + "?share=" + key), null)
        .andExpect(status().isForbidden());
    call(null, get("/api/blogs/by-slug/" + slug + "?share=" + newKey), null)
        .andExpect(status().isOk());
    // 일반 회원은 링크를 새로 만들 수 없다
    call(newUser(), post("/api/blogs/" + blogId + "/share-link"), null)
        .andExpect(status().isForbidden());
  }

  @Test
  void privateIsMembersOnlyAndHiddenOrClosedIsNotFound() throws Exception {
    User owner = newUser();
    String slug = slug();
    call(owner, post("/api/blogs"), blogBody(slug, "PRIVATE", "OPEN", List.of()))
        .andExpect(status().isCreated());

    call(null, get("/api/blogs/by-slug/" + slug), null).andExpect(status().isForbidden());
    call(newUser(), get("/api/blogs/by-slug/" + slug), null).andExpect(status().isForbidden());
    call(owner, get("/api/blogs/by-slug/" + slug), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewer.role").value("OWNER"));

    jdbc.update("update blogs set is_hidden = true where slug = ?", slug);
    call(owner, get("/api/blogs/by-slug/" + slug), null).andExpect(status().isNotFound());
    jdbc.update("update blogs set is_hidden = false, status = 'CLOSED' where slug = ?", slug);
    call(owner, get("/api/blogs/by-slug/" + slug), null).andExpect(status().isNotFound());
  }

  @Test
  void suspendedMemberIsToldUntilWhen() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner, slug(), "PUBLIC", "OPEN");
    call(member, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
    jdbc.update(
        "update blog_members set suspended_until = ? where blog_id = ? and user_id = ?",
        LocalDateTime.now().plusDays(3),
        blogId,
        member.getId());

    call(member, get("/api/blogs/by-slug/" + slugOf(blogId)), null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(containsString("까지 정지되어")));
  }

  // ---------- 목록·검색 (T048, T049) ----------

  @Test
  void listShowsOnlyPublicAndSortsPopularByMembers() throws Exception {
    User owner = newUser();
    long small = createBlog(owner, slug(), "PUBLIC", "OPEN");
    long big = createBlog(owner, slug(), "PUBLIC", "OPEN");
    long hidden = createBlog(owner, slug(), "LINK_ONLY", "OPEN");
    jdbc.update("update blogs set member_count = 100000 where id = ?", big);

    call(null, get("/api/blogs?sort=popular"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(big))
        .andExpect(jsonPath("$.page").value(1));
    call(null, get("/api/blogs?sort=latest&page=1"), null)
        .andExpect(jsonPath("$.items[*].id", hasItem((int) small)))
        .andExpect(jsonPath("$.items[*].id", not(hasItem((int) hidden))));
  }

  @Test
  void searchByNameDescriptionAndTag() throws Exception {
    User owner = newUser();
    String word = "검색" + UUID.randomUUID().toString().substring(0, 6);
    String byName = slug();
    String byTag = slug();
    String privateSlug = slug();
    call(owner, post("/api/blogs"), blogBody(byName, word + " 모임", "PUBLIC", "OPEN", List.of()))
        .andExpect(status().isCreated());
    call(owner, post("/api/blogs"), blogBody(byTag, "다른 이름", "PUBLIC", "OPEN", List.of(word)))
        .andExpect(status().isCreated());
    call(owner, post("/api/blogs"), blogBody(privateSlug, word, "PRIVATE", "OPEN", List.of()))
        .andExpect(status().isCreated());

    JsonNode result = body(call(null, get("/api/blogs/search?q=" + word), null));
    List<String> slugs = new ArrayList<>();
    result.get("items").forEach(n -> slugs.add(n.get("slug").asString()));
    assertThat(slugs).contains(byName, byTag).doesNotContain(privateSlug);

    call(null, get("/api/blogs/search?q=a"), null).andExpect(status().isBadRequest());
    call(null, get("/api/blogs/search").param("q", "++"), null).andExpect(status().isBadRequest());
  }

  // ---------- 참여 (T050, T051) ----------

  @Test
  void openJoinMakesMemberRightAway() throws Exception {
    User owner = newUser();
    User joiner = newUser();
    long blogId = createBlog(owner, slug(), "PUBLIC", "OPEN");

    call(joiner, post("/api/blogs/" + blogId + "/join"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.joined").value(true));
    call(joiner, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isConflict());
    assertThat(memberCount(blogId)).isEqualTo(2);
  }

  @Test
  void approvalJoinFlowWithRejectWaitAndApprove() throws Exception {
    User owner = newUser();
    User joiner = newUser();
    long blogId = createBlog(owner, slug(), "PUBLIC", "APPROVAL");

    long requestId =
        body(call(joiner, post("/api/blogs/" + blogId + "/join"), null)).get("requestId").asLong();
    call(joiner, post("/api/blogs/" + blogId + "/join"), null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("승인을 기다려")));
    assertThat(notifications(owner, "JOIN_REQUEST")).isEqualTo(1);

    // 일반 회원은 처리할 수 없다
    call(joiner, post("/api/blogs/" + blogId + "/join-requests/" + requestId + "/approve"), null)
        .andExpect(status().isForbidden());
    call(owner, get("/api/blogs/" + blogId + "/join-requests"), null)
        .andExpect(jsonPath("$[0].id").value(requestId));

    call(owner, post("/api/blogs/" + blogId + "/join-requests/" + requestId + "/reject"), null)
        .andExpect(status().isNoContent());
    assertThat(notifications(joiner, "JOIN_RESULT")).isEqualTo(1);
    call(joiner, post("/api/blogs/" + blogId + "/join"), null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("이후에 다시 신청")));
    call(joiner, get("/api/blogs/by-slug/" + slugOf(blogId)), null)
        .andExpect(jsonPath("$.viewer.reapplyAt").exists());

    jdbc.update(
        "update blog_join_requests set handled_at = ? where id = ?",
        LocalDateTime.now().minusDays(8),
        requestId);
    long second =
        body(call(joiner, post("/api/blogs/" + blogId + "/join"), null)).get("requestId").asLong();
    call(owner, post("/api/blogs/" + blogId + "/join-requests/" + second + "/approve"), null)
        .andExpect(status().isNoContent());
    call(joiner, get("/api/blogs/by-slug/" + slugOf(blogId)), null)
        .andExpect(jsonPath("$.viewer.role").value("MEMBER"));
    assertThat(memberCount(blogId)).isEqualTo(2);
  }

  @Test
  void onlyApplicantCanCancel() throws Exception {
    User owner = newUser();
    User joiner = newUser();
    long blogId = createBlog(owner, slug(), "PUBLIC", "APPROVAL");
    long requestId =
        body(call(joiner, post("/api/blogs/" + blogId + "/join"), null)).get("requestId").asLong();

    call(newUser(), delete("/api/blogs/" + blogId + "/join-requests/" + requestId), null)
        .andExpect(status().isForbidden());
    call(joiner, delete("/api/blogs/" + blogId + "/join-requests/" + requestId), null)
        .andExpect(status().isNoContent());
    // 취소한 뒤에는 바로 다시 신청할 수 있다
    call(joiner, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
  }

  @Test
  void managerWithPermissionApprovesAndPlainManagerCannot() throws Exception {
    User owner = newUser();
    User mgr = newUser();
    User plainMgr = newUser();
    User joiner = newUser();
    long blogId = createBlog(owner, slug(), "PUBLIC", "APPROVAL");
    long mgrRow = addManager(blogId, mgr);
    addManager(blogId, plainMgr);
    jdbc.update(
        "insert into blog_manager_permissions (blog_member_id, permission) values (?, 'MANAGE_MEMBERS')",
        mgrRow);

    long requestId =
        body(call(joiner, post("/api/blogs/" + blogId + "/join"), null)).get("requestId").asLong();
    assertThat(notifications(mgr, "JOIN_REQUEST")).isEqualTo(1);
    assertThat(notifications(plainMgr, "JOIN_REQUEST")).isZero();

    call(plainMgr, post("/api/blogs/" + blogId + "/join-requests/" + requestId + "/approve"), null)
        .andExpect(status().isForbidden());
    call(mgr, post("/api/blogs/" + blogId + "/join-requests/" + requestId + "/approve"), null)
        .andExpect(status().isNoContent());
  }

  @Test
  void suspendedOwnerWithoutManagersPausesJoins() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, slug(), "PUBLIC", "OPEN");
    jdbc.update(
        "update users set suspended_until = ? where id = ?",
        LocalDateTime.now().plusDays(3),
        owner.getId());

    call(newUser(), post("/api/blogs/" + blogId + "/join"), null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(containsString("블로그장 정지로 참여 신청이 일시 중지되었어요")));
    call(null, get("/api/blogs/by-slug/" + slugOf(blogId)), null)
        .andExpect(jsonPath("$.ownerSuspendedUntil").exists());
  }

  @Test
  void suspendedOwnerWithManagerSendsRequestToAllManagers() throws Exception {
    User owner = newUser();
    User mgr = newUser();
    long blogId = createBlog(owner, slug(), "PUBLIC", "APPROVAL");
    addManager(blogId, mgr);
    jdbc.update(
        "update users set suspended_until = ? where id = ?",
        LocalDateTime.now().plusDays(3),
        owner.getId());

    call(newUser(), post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
    assertThat(notifications(mgr, "JOIN_REQUEST")).isEqualTo(1);
    assertThat(notifications(owner, "JOIN_REQUEST")).isZero();
  }

  @Test
  void linkOnlyJoinNeedsShareKey() throws Exception {
    User owner = newUser();
    JsonNode created =
        body(call(owner, post("/api/blogs"), blogBody(slug(), "LINK_ONLY", "OPEN", List.of())));
    long blogId = created.get("id").asLong();
    User joiner = newUser();

    call(joiner, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isForbidden());
    call(
            joiner,
            post("/api/blogs/" + blogId + "/join?share=" + created.get("shareKey").asString()),
            null)
        .andExpect(status().isOk());
  }

  // ---------- 내 블로그·정보 수정 (T052, T053) ----------

  @Test
  void myBlogsSplitsOwnedJoinedAndPending() throws Exception {
    User me = newUser();
    long mine = createBlog(me, slug(), "PUBLIC", "OPEN");
    long joined = createBlog(newUser(), slug(), "PUBLIC", "OPEN");
    long waiting = createBlog(newUser(), slug(), "PUBLIC", "APPROVAL");
    call(me, post("/api/blogs/" + joined + "/join"), null).andExpect(status().isOk());
    call(me, post("/api/blogs/" + waiting + "/join"), null).andExpect(status().isOk());

    call(me, get("/api/me/blogs"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owned[0].id").value(mine))
        .andExpect(jsonPath("$.joined[0].blog.id").value(joined))
        .andExpect(jsonPath("$.joined[0].role").value("MEMBER"))
        .andExpect(jsonPath("$.pending[0].id").value(waiting));
  }

  @Test
  void updateNeedsEditInfoAndManagesShareKey() throws Exception {
    User owner = newUser();
    User member = newUser();
    String slug = slug();
    long blogId = createBlog(owner, slug, "PUBLIC", "OPEN");
    call(member, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());

    String body = updateBody("새 이름", "LINK_ONLY", "APPROVAL", List.of("새태그"));
    call(member, put("/api/blogs/" + blogId), body).andExpect(status().isForbidden());
    JsonNode res = body(call(owner, put("/api/blogs/" + blogId), body));
    assertThat(res.get("shareKey").asString()).isNotBlank();

    call(owner, get("/api/blogs/by-slug/" + slug), null)
        .andExpect(jsonPath("$.name").value("새 이름"))
        .andExpect(jsonPath("$.joinPolicy").value("APPROVAL"))
        .andExpect(jsonPath("$.tags[0]").value("새태그"));

    JsonNode back =
        body(
            call(
                owner,
                put("/api/blogs/" + blogId),
                updateBody("새 이름", "PUBLIC", "OPEN", List.of())));
    assertThat(back.get("shareKey").isNull()).isTrue();
  }

  @Test
  void blogAddressShowsBlogPage() throws Exception {
    mockMvc.perform(get("/blog/some-blog")).andExpect(forwardedUrl("/blog.html"));
    mockMvc.perform(get("/blog/some-blog/admin")).andExpect(forwardedUrl("/blog-admin.html"));
  }

  // ---------- 도우미 ----------

  private ResultActions call(User user, MockHttpServletRequestBuilder request, String json)
      throws Exception {
    request.with(csrf());
    if (user != null) {
      AuthUser principal = new AuthUser(user.getId(), UserRole.USER);
      request.with(
          authentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
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
    return body(call(owner, post("/api/blogs"), blogBody(slug, visibility, joinPolicy, List.of()))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private long addManager(long blogId, User user) {
    jdbc.update(
        "insert into blog_members (blog_id, user_id, role, manager_since) values (?, ?, 'MANAGER', now())",
        blogId,
        user.getId());
    return jdbc.queryForObject(
        "select id from blog_members where blog_id = ? and user_id = ?",
        Long.class,
        blogId,
        user.getId());
  }

  private String slugOf(long blogId) {
    return jdbc.queryForObject("select slug from blogs where id = ?", String.class, blogId);
  }

  private int memberCount(long blogId) {
    return jdbc.queryForObject(
        "select member_count from blogs where id = ?", Integer.class, blogId);
  }

  private int notifications(User receiver, String type) {
    return jdbc.queryForObject(
        "select count(*) from notifications where receiver_id = ? and type = ?",
        Integer.class,
        receiver.getId(),
        type);
  }

  private String blogBody(String slug, String visibility, String joinPolicy, List<String> tags) {
    return blogBody(slug, "테스트 블로그", visibility, joinPolicy, tags);
  }

  private String blogBody(
      String slug, String name, String visibility, String joinPolicy, List<String> tags) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", slug);
    body.put("name", name);
    body.put("description", "소개");
    body.put("visibility", visibility);
    body.put("joinPolicy", joinPolicy);
    body.put("tags", tags);
    return jsonMapper.writeValueAsString(body);
  }

  private String updateBody(String name, String visibility, String joinPolicy, List<String> tags) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("description", "소개");
    body.put("visibility", visibility);
    body.put("joinPolicy", joinPolicy);
    body.put("tags", tags);
    return jsonMapper.writeValueAsString(body);
  }

  private static String slug() {
    return "t-" + UUID.randomUUID().toString().substring(0, 12);
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "b" + unique + "@example.com",
            "hash",
            "이름",
            "b" + unique,
            "01012345678",
            LocalDateTime.now()));
  }
}
