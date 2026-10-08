package com.blog.blog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
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

/** 블로그장의 멤버 관리·신고·블랙리스트를 실제 API로 확인한다 (T072~T084, BLG-09~13). */
@IntegrationTest
class MemberAdminFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;

  @Test
  void suspensionBlocksOnlyThatBlogAndShowsTheReason() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    long otherBlog = createBlog(owner);
    join(member, blogId);
    join(member, otherBlog);
    long postId = writePost(owner, blogId, "공지");

    call(owner, post(members(blogId) + member.getId() + "/suspend"), sanction("도배", 30))
        .andExpect(status().isNoContent());

    call(member, get("/api/posts/" + postId), null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(containsString("사유: 도배")));
    writePost(member, otherBlog, "다른 블로그는 괜찮아요");
    assertThat(notifications(member, "MEMBER_SANCTION")).isEqualTo(1);

    call(owner, get("/api/blogs/" + blogId + "/members"), null)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[?(@.userId == %d)].suspensionCount".formatted(member.getId())).value(1))
        .andExpect(
            jsonPath("$[?(@.userId == %d)].email".formatted(member.getId()))
                .value(hasItem(containsString("*"))));

    call(owner, post(members(blogId) + member.getId() + "/release"), null)
        .andExpect(status().isNoContent());
    call(member, get("/api/posts/" + postId), null).andExpect(status().isOk());

    call(owner, post(members(blogId) + member.getId() + "/suspend"), sanction("도배", 5))
        .andExpect(status().isBadRequest());
    call(owner, post(members(blogId) + member.getId() + "/warn"), sanction("", null))
        .andExpect(status().isBadRequest());
  }

  @Test
  void onlyOwnerAppointsManagersAndSanctionsThem() throws Exception {
    User owner = newUser();
    User manager = newUser();
    User otherManager = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(manager, blogId);
    join(otherManager, blogId);
    join(member, blogId);

    call(owner, put(managers(blogId) + manager.getId()), managerBody(true, "MANAGE_MEMBERS"))
        .andExpect(status().isNoContent());
    call(owner, put(managers(blogId) + otherManager.getId()), managerBody(true, "EDIT_INFO"))
        .andExpect(status().isNoContent());
    call(manager, put(managers(blogId) + member.getId()), managerBody(true, "MANAGE_POSTS"))
        .andExpect(status().isForbidden());

    call(manager, post(members(blogId) + member.getId() + "/warn"), sanction("욕설", null))
        .andExpect(status().isNoContent());
    call(manager, post(members(blogId) + otherManager.getId() + "/warn"), sanction("욕설", null))
        .andExpect(status().isForbidden());
    call(manager, post(members(blogId) + owner.getId() + "/warn"), sanction("욕설", null))
        .andExpect(status().isForbidden());
    call(otherManager, get("/api/blogs/" + blogId + "/members"), null)
        .andExpect(status().isForbidden());

    call(owner, get("/api/blogs/" + blogId + "/members"), null)
        .andExpect(jsonPath("$[0].role").value("OWNER"))
        .andExpect(
            jsonPath("$[?(@.userId == %d)].permissions[0]".formatted(manager.getId()))
                .value(hasItem("MANAGE_MEMBERS")));

    call(owner, put(managers(blogId) + manager.getId()), managerBody(false))
        .andExpect(status().isNoContent());
    call(manager, get("/api/blogs/" + blogId + "/members"), null).andExpect(status().isForbidden());
  }

  @Test
  void kickedMemberIsBlacklistedUntilTheOwnerReleasesTheInquiry() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    long postId = writePost(member, blogId, "강퇴될 글");

    call(owner, post(members(blogId) + member.getId() + "/kick"), sanction("광고", null))
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "select member_count from blogs where id = ?", Integer.class, blogId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select author_hidden from posts where id = ?", Boolean.class, postId))
        .isTrue();

    call(member, post("/api/blogs/" + blogId + "/join"), null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("BLACKLISTED"));
    User samePhone = newUser(member.getPhone());
    call(samePhone, post("/api/blogs/" + blogId + "/join"), null)
        .andExpect(jsonPath("$.code").value("BLACKLISTED"));
    call(newUser(), post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());

    call(member, post(blacklist(blogId)), "{\"message\":\"실수였어요\"}")
        .andExpect(status().isCreated());
    call(member, post(blacklist(blogId)), "{\"message\":\"다시\"}").andExpect(status().isConflict());

    JsonNode view =
        body(
            call(owner, get("/api/blogs/" + blogId + "/blacklist"), null)
                .andExpect(status().isOk()));
    assertThat(view.get("records").size()).isEqualTo(1);
    JsonNode inquiry = view.get("inquiries").get(0);
    assertThat(inquiry.get("phoneMatch").asBoolean()).isTrue();
    call(member, get("/api/blogs/" + blogId + "/blacklist"), null)
        .andExpect(status().isForbidden());

    call(owner, post(blacklist(blogId) + "/" + inquiry.get("id").asLong() + "/release"), null)
        .andExpect(status().isNoContent());
    assertThat(notifications(member, "BLACKLIST_INQUIRY_RESULT")).isEqualTo(1);
    join(member, blogId);
  }

  @Test
  void reportsGoToTheRightHandlerAndOnlyOncePerTwoWeeks() throws Exception {
    User owner = newUser();
    User member = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    long memberPost = writePost(member, blogId, "문제 글");
    long ownerPost = writePost(owner, blogId, "블로그장 글");

    JsonNode created =
        body(
            call(reporter, post("/api/reports"), report("POST", memberPost, blogId))
                .andExpect(status().isCreated()));
    assertThat(created.get("handlerScope").asString()).isEqualTo("BLOG_OWNER");
    call(reporter, post("/api/reports"), report("POST", memberPost, blogId))
        .andExpect(status().isConflict());
    call(reporter, post("/api/reports"), report("POST", ownerPost, blogId))
        .andExpect(jsonPath("$.handlerScope").value("ADMIN"));
    call(member, post("/api/reports"), report("POST", memberPost, blogId))
        .andExpect(status().isBadRequest());

    JsonNode pending =
        body(
            call(owner, get("/api/blogs/" + blogId + "/reports"), null).andExpect(status().isOk()));
    assertThat(pending.size()).isEqualTo(1);
    long reportId = pending.get(0).get("id").asLong();
    call(member, get("/api/blogs/" + blogId + "/reports"), null).andExpect(status().isForbidden());

    String resolve = "{\"resolution\":\"WARN\",\"reason\":\"광고 글\"}";
    call(owner, post("/api/blogs/" + blogId + "/reports/" + reportId + "/resolve"), resolve)
        .andExpect(status().isNoContent());
    call(owner, post("/api/blogs/" + blogId + "/reports/" + reportId + "/resolve"), resolve)
        .andExpect(status().isConflict());
    assertThat(notifications(reporter, "REPORT_RESULT")).isEqualTo(1);
    assertThat(notifications(member, "MEMBER_SANCTION")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select report_id from member_sanctions where user_id = ?",
                Long.class,
                member.getId()))
        .isEqualTo(reportId);
  }

  // ---------- 도우미 ----------

  private static String members(long blogId) {
    return "/api/blogs/" + blogId + "/members/";
  }

  private static String managers(long blogId) {
    return "/api/blogs/" + blogId + "/managers/";
  }

  private static String blacklist(long blogId) {
    return "/api/blogs/" + blogId + "/blacklist-inquiries";
  }

  private String sanction(String reason, Integer days) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", reason);
    body.put("days", days);
    return jsonMapper.writeValueAsString(body);
  }

  private String managerBody(boolean manager, String... permissions) {
    return jsonMapper.writeValueAsString(
        Map.of("manager", manager, "permissions", List.of(permissions)));
  }

  private String report(String targetType, long targetId, long blogId) {
    return jsonMapper.writeValueAsString(
        Map.of("targetType", targetType, "targetId", targetId, "blogId", blogId, "reason", "SPAM"));
  }

  private int notifications(User receiver, String type) {
    return jdbc.queryForObject(
        "select count(*) from notifications where receiver_id = ? and type = ?",
        Integer.class,
        receiver.getId(),
        type);
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
    return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private long createBlog(User owner) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "m-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "멤버 관리 시험");
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

  private User newUser() {
    return newUser("010" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000));
  }

  private User newUser(String phone) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "m" + unique + "@example.com", "hash", "이름", "m" + unique, phone, LocalDateTime.now()));
  }
}
