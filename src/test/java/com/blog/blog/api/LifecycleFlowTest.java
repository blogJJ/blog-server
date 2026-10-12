package com.blog.blog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** 블로그장 위임·블로그 폐쇄·블로그 떠나기·회원 탈퇴를 실제 API로 확인한다 (T087, T088, T091, T092, BLG-07~09, USR-05). */
@IntegrationTest
class LifecycleFlowTest {

  static final String PASSWORD = "Passw0rd!";

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;

  @Test
  void acceptedTransferSwapsRolesAndKeepsManagerPermissions() throws Exception {
    User owner = newUser();
    User manager = newUser();
    User outsider = newUser();
    long blogId = createBlog(owner);
    join(manager, blogId);
    call(
            owner,
            put("/api/blogs/" + blogId + "/managers/" + manager.getId()),
            "{\"manager\":true,\"permissions\":[\"MANAGE_POSTS\"]}")
        .andExpect(status().isNoContent());

    call(outsider, post("/api/blogs/" + blogId + "/transfers"), to(owner))
        .andExpect(status().isForbidden());
    call(owner, post("/api/blogs/" + blogId + "/transfers"), to(outsider))
        .andExpect(status().isNotFound());
    long transferId = requestTransfer(owner, blogId, manager);
    call(owner, post("/api/blogs/" + blogId + "/transfers"), to(manager))
        .andExpect(status().isConflict());
    call(owner, get("/api/blogs/" + blogId + "/transfers/pending"), null)
        .andExpect(jsonPath("$.toUserId").value(manager.getId()));
    call(manager, get("/api/me/transfers"), null).andExpect(jsonPath("$[0].id").value(transferId));
    assertThat(notifications(manager, "OWNER_TRANSFER_REQUEST")).isEqualTo(1);

    call(owner, post("/api/transfers/" + transferId + "/accept"), null)
        .andExpect(status().isNotFound());
    call(manager, post("/api/transfers/" + transferId + "/accept"), null)
        .andExpect(status().isNoContent());
    call(manager, post("/api/transfers/" + transferId + "/accept"), null)
        .andExpect(status().isConflict());

    assertThat(ownerId(blogId)).isEqualTo(manager.getId());
    assertThat(role(blogId, manager)).isEqualTo("OWNER");
    assertThat(role(blogId, owner)).isEqualTo("MANAGER");
    assertThat(
            jdbc.queryForObject(
                "select p.permission from blog_manager_permissions p"
                    + " join blog_members m on m.id = p.blog_member_id"
                    + " where m.blog_id = ? and m.user_id = ?",
                String.class,
                blogId,
                owner.getId()))
        .isEqualTo("MANAGE_POSTS");
    assertThat(notifications(owner, "OWNER_TRANSFER_RESULT")).isEqualTo(1);

    // 넘긴 사람은 더 이상 폐쇄할 수 없고, 새 블로그장은 할 수 있다
    call(owner, post("/api/blogs/" + blogId + "/close"), null).andExpect(status().isForbidden());
    call(manager, post("/api/blogs/" + blogId + "/close"), null).andExpect(status().isOk());
  }

  @Test
  void rejectedTransferKeepsTheOwner() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    long transferId = requestTransfer(owner, blogId, member);

    call(member, post("/api/transfers/" + transferId + "/reject"), null)
        .andExpect(status().isNoContent());
    assertThat(ownerId(blogId)).isEqualTo(owner.getId());
    assertThat(notifications(owner, "OWNER_TRANSFER_RESULT")).isEqualTo(1);
    call(owner, get("/api/blogs/" + blogId + "/transfers/pending"), null)
        .andExpect(status().isNoContent());
  }

  @Test
  void ownerCancelsPendingTransfer() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    long transferId = requestTransfer(owner, blogId, member);

    // 블로그마다 대기 중인 요청은 하나 (D-123)
    call(owner, post("/api/blogs/" + blogId + "/transfers"), to(member))
        .andExpect(status().isConflict());
    call(member, post("/api/blogs/" + blogId + "/transfers/pending/cancel"), null)
        .andExpect(status().isForbidden());
    // 메인 관리자도 위임 요청은 건드리지 못한다 (D-122)
    User admin = newUser();
    jdbc.update("update users set role = 'ADMIN' where id = ?", admin.getId());
    call(admin, UserRole.ADMIN, post("/api/blogs/" + blogId + "/transfers/pending/cancel"), null)
        .andExpect(status().isForbidden());

    call(owner, post("/api/blogs/" + blogId + "/transfers/pending/cancel"), null)
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "select status from blog_owner_transfers where id = ?", String.class, transferId))
        .isEqualTo("CANCELED");
    assertThat(notifications(member, "OWNER_TRANSFER_RESULT")).isEqualTo(1);
    call(member, post("/api/transfers/" + transferId + "/accept"), null)
        .andExpect(status().isConflict());
    call(owner, post("/api/blogs/" + blogId + "/transfers/pending/cancel"), null)
        .andExpect(status().isConflict());

    // 취소한 뒤에는 다시 요청할 수 있다
    requestTransfer(owner, blogId, member);
  }

  @Test
  void suspendedMemberCannotLeave() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    jdbc.update(
        "update blog_members set suspended_until = now() + interval 3 day where blog_id = ? and user_id = ?",
        blogId,
        member.getId());

    // 떠났다가 다시 참여해 정지를 피하지 못하게 한다 (D-125)
    call(member, post("/api/blogs/" + blogId + "/leave"), null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(containsString("정지")));

    jdbc.update(
        "update blog_members set suspended_until = null where blog_id = ? and user_id = ?",
        blogId,
        member.getId());
    call(member, post("/api/blogs/" + blogId + "/leave"), null).andExpect(status().isNoContent());
  }

  @Test
  void closingCancelsPendingTransferAndBlocksNewOnes() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    long transferId = requestTransfer(owner, blogId, member);

    call(member, post("/api/blogs/" + blogId + "/close"), null).andExpect(status().isForbidden());
    call(owner, post("/api/blogs/" + blogId + "/close"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSING"));
    call(owner, post("/api/blogs/" + blogId + "/close"), null).andExpect(status().isConflict());
    assertThat(
            jdbc.queryForObject(
                "select status from blog_owner_transfers where id = ?", String.class, transferId))
        .isEqualTo("CANCELED");
    call(member, post("/api/transfers/" + transferId + "/accept"), null)
        .andExpect(status().isConflict());

    call(owner, post("/api/blogs/" + blogId + "/transfers"), to(member))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("철회")));

    // 폐쇄 예정 중에도 글쓰기는 된다 (D-67)
    writePost(member, blogId);

    call(owner, post("/api/blogs/" + blogId + "/close/revoke"), null)
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    requestTransfer(owner, blogId, member);
  }

  @Test
  void memberLeavesAndPostsShowAsLeftAccount() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    long postId = writePost(member, blogId);
    long transferId = requestTransfer(owner, blogId, member);

    call(owner, post("/api/blogs/" + blogId + "/leave"), null).andExpect(status().isForbidden());
    call(member, post("/api/blogs/" + blogId + "/leave"), null).andExpect(status().isNoContent());
    call(member, post("/api/blogs/" + blogId + "/leave"), null).andExpect(status().isNotFound());

    assertThat(
            jdbc.queryForObject(
                "select member_count from blogs where id = ?", Integer.class, blogId))
        .isEqualTo(1);
    call(owner, get("/api/posts/" + postId), null)
        .andExpect(jsonPath("$.authorNickname").value("탈퇴한 계정"));
    assertThat(
            jdbc.queryForObject(
                "select status from blog_owner_transfers where id = ?", String.class, transferId))
        .isEqualTo("CANCELED");
  }

  @Test
  void ownerCannotWithdrawUntilBlogsAreHandedOver() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);

    call(owner, get("/api/me/withdraw"), null)
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.blogs[0].id").value(blogId));
    call(owner, post("/api/me/withdraw"), withdrawBody(PASSWORD))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WITHDRAW_BLOCKED"))
        .andExpect(jsonPath("$.blogs[0].id").value(blogId))
        .andExpect(jsonPath("$.guide").exists());

    // 폐쇄 예정 중에도 막힌다 (D-18)
    call(owner, post("/api/blogs/" + blogId + "/close"), null).andExpect(status().isOk());
    call(owner, post("/api/me/withdraw"), withdrawBody(PASSWORD))
        .andExpect(jsonPath("$.blogs[0].status").value("CLOSING"));

    call(owner, post("/api/blogs/" + blogId + "/close/revoke"), null).andExpect(status().isOk());
    long transferId = requestTransfer(owner, blogId, member);
    call(member, post("/api/transfers/" + transferId + "/accept"), null)
        .andExpect(status().isNoContent());
    call(owner, get("/api/me/withdraw"), null).andExpect(jsonPath("$.allowed").value(true));
  }

  @Test
  void withdrawalFreesTheEmailAndKeepsPosts() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    long postId = writePost(member, blogId);
    String email = member.getEmail();
    jdbc.update(
        "insert into refresh_tokens (user_id, token_hash, expires_at) values (?, ?, ?)",
        member.getId(),
        "w-" + UUID.randomUUID(),
        LocalDateTime.now().plusDays(1));

    call(member, post("/api/me/withdraw"), withdrawBody("Wrong123!"))
        .andExpect(status().isBadRequest());
    call(member, post("/api/me/withdraw"), withdrawBody(PASSWORD))
        .andExpect(status().isNoContent());

    assertThat(
            jdbc.queryForMap(
                "select status, email, withdrawn_at from users where id = ?", member.getId()))
        .containsEntry("status", "WITHDRAWN")
        .containsEntry("email", null)
        .extractingByKey("withdrawn_at")
        .isNotNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class,
                member.getId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select member_count from blogs where id = ?", Integer.class, blogId))
        .isEqualTo(1);
    call(owner, get("/api/posts/" + postId), null)
        .andExpect(jsonPath("$.authorNickname").value("탈퇴한 회원"));

    // 같은 이메일로 바로 다시 가입할 수 있다
    userRepository.save(
        new User(email, "hash", "이름", "r" + uniq(), randomPhone(), LocalDateTime.now()));
  }

  // ---------- 도우미 ----------

  private String to(User user) {
    return jsonMapper.writeValueAsString(Map.of("toUserId", user.getId()));
  }

  private String withdrawBody(String password) {
    return jsonMapper.writeValueAsString(Map.of("password", password));
  }

  private long ownerId(long blogId) {
    return jdbc.queryForObject("select owner_id from blogs where id = ?", Long.class, blogId);
  }

  private String role(long blogId, User user) {
    return jdbc.queryForObject(
        "select role from blog_members where blog_id = ? and user_id = ?",
        String.class,
        blogId,
        user.getId());
  }

  private int notifications(User receiver, String type) {
    return jdbc.queryForObject(
        "select count(*) from notifications where receiver_id = ? and type = ?",
        Integer.class,
        receiver.getId(),
        type);
  }

  private long requestTransfer(User owner, long blogId, User to) throws Exception {
    return id(
        call(owner, post("/api/blogs/" + blogId + "/transfers"), to(to))
            .andExpect(status().isCreated()));
  }

  private long id(ResultActions result) throws Exception {
    return jsonMapper
        .readTree(result.andReturn().getResponse().getContentAsString())
        .get("id")
        .asLong();
  }

  private ResultActions call(User user, MockHttpServletRequestBuilder request, String json)
      throws Exception {
    return call(user, UserRole.USER, request, json);
  }

  private ResultActions call(
      User user, UserRole role, MockHttpServletRequestBuilder request, String json)
      throws Exception {
    request.with(csrf());
    request.with(
        authentication(
            UsernamePasswordAuthenticationToken.authenticated(
                new AuthUser(user.getId(), role),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())))));
    if (json != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mockMvc.perform(request);
  }

  private long createBlog(User owner) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "l-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "위임 폐쇄 시험");
    body.put("visibility", "PUBLIC");
    body.put("joinPolicy", "OPEN");
    body.put("tags", List.of());
    return id(
        call(owner, post("/api/blogs"), jsonMapper.writeValueAsString(body))
            .andExpect(status().isCreated()));
  }

  private void join(User user, long blogId) throws Exception {
    call(user, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
  }

  private long writePost(User user, long blogId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "글");
    body.put("content", "본문");
    body.put("tags", List.of());
    body.put("notice", false);
    return id(
        call(user, post("/api/blogs/" + blogId + "/posts"), jsonMapper.writeValueAsString(body))
            .andExpect(status().isCreated()));
  }

  private User newUser() {
    String unique = uniq();
    return userRepository.save(
        new User(
            "l" + unique + "@example.com",
            passwordEncoder.encode(PASSWORD),
            "이름",
            "l" + unique,
            randomPhone(),
            LocalDateTime.now()));
  }

  private static String uniq() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private static String randomPhone() {
    return "010" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
  }
}
