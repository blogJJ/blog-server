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

  @Test
  @DisplayName("정지는 받을 때마다 횟수가 쌓이고 3번째에 강제 퇴장 권유가 뜬다. 영구 정지는 9999년까지 (D-46, D-57)")
  void thirdSuspensionSuggestsKick() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);

    suspendAndRelease(owner, blogId, member, 3);
    suspendAndRelease(owner, blogId, member, 14);
    call(owner, get("/api/blogs/" + blogId + "/members"), null)
        .andExpect(
            jsonPath("$[?(@.userId == %d)].suspensionCount".formatted(member.getId())).value(2))
        .andExpect(
            jsonPath("$[?(@.userId == %d)].kickSuggested".formatted(member.getId()))
                .value(hasItem(false)));

    // 기간을 고르지 않으면 영구 정지
    call(owner, post(members(blogId) + member.getId() + "/suspend"), sanction("또 도배", null))
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "select year(suspended_until) from blog_members where blog_id = ? and user_id = ?",
                Integer.class,
                blogId,
                member.getId()))
        .isEqualTo(9999);
    assertThat(
            jdbc.queryForObject(
                "select message from notifications where receiver_id = ? and type = 'MEMBER_SANCTION'"
                    + " order by id desc limit 1",
                String.class,
                member.getId()))
        .contains("(영구)")
        .contains("사유: 또 도배");
    call(owner, get("/api/blogs/" + blogId + "/members"), null)
        .andExpect(
            jsonPath("$[?(@.userId == %d)].suspensionCount".formatted(member.getId())).value(3))
        .andExpect(
            jsonPath("$[?(@.userId == %d)].kickSuggested".formatted(member.getId()))
                .value(hasItem(true)));

    // 권유일 뿐 자동으로 빼지 않는다. 퇴장은 블로그장이 누른다
    assertThat(
            jdbc.queryForObject(
                "select count(*) from blog_members where blog_id = ? and user_id = ?",
                Integer.class,
                blogId,
                member.getId()))
        .isEqualTo(1);
    call(owner, post(members(blogId) + member.getId() + "/kick"), sanction("정지 3회", null))
        .andExpect(status().isNoContent());
    call(owner, get("/api/blogs/" + blogId + "/members/" + member.getId() + "/sanctions"), null)
        .andExpect(jsonPath("$.length()").value(4))
        .andExpect(jsonPath("$[?(@.type == 'SUSPENSION' && @.suspendDays == null)]").exists());
  }

  @Test
  @DisplayName("자기 자신은 제재할 수 없고, 멤버가 아니면 제재할 수 없으며, 정지 중이 아니면 해제할 수 없다 (BLG-13)")
  void sanctionTargetMustBeAnotherMemberAndReleaseNeedsASuspension() throws Exception {
    User owner = newUser();
    User manager = newUser();
    User member = newUser();
    User outsider = newUser();
    long blogId = createBlog(owner);
    join(manager, blogId);
    join(member, blogId);
    call(owner, put(managers(blogId) + manager.getId()), managerBody(true, "MANAGE_MEMBERS"))
        .andExpect(status().isNoContent());

    call(manager, post(members(blogId) + manager.getId() + "/warn"), sanction("나", null))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(containsString("자기 자신")));
    call(owner, post(members(blogId) + owner.getId() + "/suspend"), sanction("나", 3))
        .andExpect(status().isForbidden());
    call(owner, post(members(blogId) + outsider.getId() + "/warn"), sanction("광고", null))
        .andExpect(status().isNotFound());
    call(owner, post(members(blogId) + outsider.getId() + "/kick"), sanction("광고", null))
        .andExpect(status().isNotFound());
    call(owner, post(members(blogId) + member.getId() + "/release"), null)
        .andExpect(status().isConflict());

    // 사유는 1~500자
    call(owner, post(members(blogId) + member.getId() + "/warn"), "{}")
        .andExpect(status().isBadRequest());
    call(owner, post(members(blogId) + member.getId() + "/warn"), sanction("가".repeat(501), null))
        .andExpect(status().isBadRequest());
    call(owner, post(members(blogId) + member.getId() + "/warn"), sanction("가".repeat(500), null))
        .andExpect(status().isNoContent());
    assertThat(notifications(member, "MEMBER_SANCTION")).isEqualTo(1);
    assertThat(notifications(outsider, "MEMBER_SANCTION")).isZero();
  }

  @Test
  @DisplayName("신고 처리 경고는 멤버가 아닌 댓글 작성자에게도 주지만, 정지는 그 블로그 멤버에게만 준다 (D-43)")
  void reportWarningReachesNonMemberButSuspensionNeedsMembership() throws Exception {
    User owner = newUser();
    User visitor = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner);
    long postId = writePost(owner, blogId, "공개 글");
    long first = comment(visitor, postId);
    long second = comment(visitor, postId);
    long warnReport = reportId(reporter, "COMMENT", first, blogId);
    long suspendReport = reportId(reporter, "COMMENT", second, blogId);

    call(
            owner,
            post("/api/blogs/" + blogId + "/reports/" + suspendReport + "/resolve"),
            "{\"resolution\":\"SUSPEND\",\"days\":3,\"reason\":\"욕설\"}")
        .andExpect(status().isNotFound());
    call(
            owner,
            post("/api/blogs/" + blogId + "/reports/" + warnReport + "/resolve"),
            "{\"resolution\":\"WARN\",\"reason\":\"욕설\"}")
        .andExpect(status().isNoContent());

    assertThat(notifications(visitor, "MEMBER_SANCTION")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select type from member_sanctions where blog_id = ? and user_id = ?",
                String.class,
                blogId,
                visitor.getId()))
        .isEqualTo("WARNING");
  }

  @Test
  @DisplayName("번호를 새로 받은 사람은 전화번호만 일치로 보이고, 거절하면 블랙리스트에 남아 다시 문의할 수 있다 (D-34, BLG-12)")
  void recycledPhoneShowsOnlyPhoneMatchAndRejectKeepsTheBlock() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    kick(owner, blogId, member);

    User newPhoneOwner = newUser(member.getPhone(), "다른사람");
    call(newPhoneOwner, post("/api/blogs/" + blogId + "/join"), null)
        .andExpect(jsonPath("$.code").value("BLACKLISTED"));
    // 문의 내용 없이도 남길 수 있다
    call(newPhoneOwner, post(blacklist(blogId)), null).andExpect(status().isCreated());

    JsonNode inquiry =
        body(call(owner, get("/api/blogs/" + blogId + "/blacklist"), null)).get("inquiries").get(0);
    assertThat(inquiry.get("nameMatch").asBoolean()).isFalse();
    assertThat(inquiry.get("phoneMatch").asBoolean()).isTrue();
    assertThat(inquiry.get("message").isNull()).isTrue();
    long inquiryId = inquiry.get("id").asLong();

    // 다른 블로그 주소로는 이 문의를 처리할 수 없다
    User otherOwner = newUser();
    long otherBlog = createBlog(otherOwner);
    call(otherOwner, post(blacklist(otherBlog) + "/" + inquiryId + "/reject"), null)
        .andExpect(status().isNotFound());

    call(owner, post(blacklist(blogId) + "/" + inquiryId + "/reject"), null)
        .andExpect(status().isNoContent());
    call(owner, post(blacklist(blogId) + "/" + inquiryId + "/release"), null)
        .andExpect(status().isConflict());
    assertThat(
            jdbc.queryForObject(
                "select message from notifications where receiver_id = ?"
                    + " and type = 'BLACKLIST_INQUIRY_RESULT'",
                String.class,
                newPhoneOwner.getId()))
        .contains("거절");
    call(newPhoneOwner, post("/api/blogs/" + blogId + "/join"), null)
        .andExpect(jsonPath("$.code").value("BLACKLISTED"));
    // 거절된 뒤에는 다시 문의할 수 있다
    call(newPhoneOwner, post(blacklist(blogId)), "{\"message\":\"번호를 새로 받았어요\"}")
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName("탈퇴 후 같은 이메일로 다시 가입해도 블랙리스트에 걸린다. 이름은 일치, 바뀐 번호는 불일치로 보인다 (D-34, BLG-11)")
  void reRegisteredEmailIsStillBlacklisted() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    kick(owner, blogId, member);
    jdbc.update(
        "update users set status = 'WITHDRAWN', email = null, withdrawn_at = now() where id = ?",
        member.getId());

    User again =
        userRepository.save(
            new User(
                member.getEmail().toUpperCase(),
                "hash",
                "이름",
                "r" + UUID.randomUUID().toString().substring(0, 8),
                "010" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000),
                LocalDateTime.now()));
    call(again, post("/api/blogs/" + blogId + "/join"), null)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("BLACKLISTED"));

    call(again, post(blacklist(blogId)), "{\"message\":\"" + "가".repeat(501) + "\"}")
        .andExpect(status().isBadRequest());
    // 공백만 쓴 문의는 내용 없음으로 저장한다
    call(again, post(blacklist(blogId)), "{\"message\":\"   \"}").andExpect(status().isCreated());
    JsonNode inquiry =
        body(call(owner, get("/api/blogs/" + blogId + "/blacklist"), null)).get("inquiries").get(0);
    assertThat(inquiry.get("nameMatch").asBoolean()).isTrue();
    assertThat(inquiry.get("phoneMatch").asBoolean()).isFalse();
    assertThat(inquiry.get("message").isNull()).isTrue();

    // 걸리지 않은 회원은 문의할 것이 없다
    call(newUser(), post(blacklist(blogId)), "{\"message\":\"저도요\"}")
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("전화번호 없는 회원을 강퇴해도 전화번호 없는 다른 회원은 걸리지 않는다. 강퇴된 본인은 이메일로 걸린다 (D-34, D-128)")
  void kickingAPhonelessMemberDoesNotBlockOtherPhonelessMembers() throws Exception {
    User owner = newUser();
    User phoneless = newUser(null, "소셜");
    long blogId = createBlog(owner);
    join(phoneless, blogId);
    kick(owner, blogId, phoneless);

    call(phoneless, post("/api/blogs/" + blogId + "/join"), null)
        .andExpect(jsonPath("$.code").value("BLACKLISTED"));
    join(newUser(null, "다른소셜"), blogId);

    call(phoneless, post(blacklist(blogId)), "{\"message\":\"다시 들어가고 싶어요\"}")
        .andExpect(status().isCreated());
    JsonNode inquiry =
        body(call(owner, get("/api/blogs/" + blogId + "/blacklist"), null)).get("inquiries").get(0);
    assertThat(inquiry.get("nameMatch").asBoolean()).isTrue();
    assertThat(inquiry.get("phoneMatch").asBoolean()).isFalse();
  }

  // ---------- 도우미 ----------

  private void suspendAndRelease(User owner, long blogId, User member, int days) throws Exception {
    call(owner, post(members(blogId) + member.getId() + "/suspend"), sanction("도배", days))
        .andExpect(status().isNoContent());
    call(owner, post(members(blogId) + member.getId() + "/release"), null)
        .andExpect(status().isNoContent());
  }

  private void kick(User owner, long blogId, User member) throws Exception {
    call(owner, post(members(blogId) + member.getId() + "/kick"), sanction("광고", null))
        .andExpect(status().isNoContent());
  }

  private long comment(User user, long postId) throws Exception {
    return body(call(user, post("/api/posts/" + postId + "/comments"), "{\"content\":\"댓글\"}")
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private long reportId(User reporter, String targetType, long targetId, long blogId)
      throws Exception {
    return body(call(reporter, post("/api/reports"), report(targetType, targetId, blogId))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

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
    return newUser(phone, "이름");
  }

  private User newUser(String phone, String name) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "m" + unique + "@example.com", "hash", name, "m" + unique, phone, LocalDateTime.now()));
  }
}
