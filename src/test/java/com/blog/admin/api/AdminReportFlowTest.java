package com.blog.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import com.blog.common.storage.FileStorage;
import com.blog.support.IntegrationTest;
import java.io.ByteArrayInputStream;
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

/**
 * 메인 관리자 신고 처리와 계정 제재를 실제 API로 확인한다 (T121, T123, ADM-04, ADM-07, ADM-08, D-94, D-95, D-99, D-100,
 * D-114).
 */
@IntegrationTest
class AdminReportFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;
  @Autowired FileStorage storage;

  @Test
  @DisplayName("ADM-04: 일반 회원은 관리자 신고 목록을 보거나 처리할 수 없다(403)")
  void onlyAdminsListAndResolveReports() throws Exception {
    User user = newUser();
    long reportId = reportId(newUser(), "USER", newUser().getId(), null, "ADMIN");

    call(user, get("/api/admin/reports"), null).andExpect(status().isForbidden());
    resolve(user, reportId, "NO_ISSUE", null, null).andExpect(status().isForbidden());
    assertThat(reportStatus(reportId)).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("ADM-04: 블로그장이 처리할 신고는 관리자 목록에 없고 관리자 신고 처리로도 처리할 수 없다(404)")
  void blogOwnerScopeReportsAreNotInTheAdminQueue() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    long inBlog =
        reportId(newUser(), "POST", writePost(member, blogId, "멤버 글"), blogId, "BLOG_OWNER");

    JsonNode items = body(call(admin, get("/api/admin/reports"), null)).get("items");
    for (JsonNode item : items) {
      assertThat(item.get("id").asLong()).isNotEqualTo(inBlog);
    }
    resolve(admin, inBlog, "NO_ISSUE", null, null).andExpect(status().isNotFound());
    resolve(admin, Long.MAX_VALUE, "NO_ISSUE", null, null).andExpect(status().isNotFound());
    assertThat(reportStatus(inBlog)).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("ADM-04: 처리 결과는 꼭 고르고, 문제 없음이 아니면 사유를 1~500자로 쓴다. 문제 없음은 사유 없이 처리돼 처리한 목록으로 옮겨진다")
  void resolutionAndReasonAreValidatedAndNoIssueMovesToDoneList() throws Exception {
    User admin = newAdmin();
    User reporter = newUser();
    User target = newUser();
    long reportId = reportId(reporter, "USER", target.getId(), null, "ADMIN");

    resolve(admin, reportId, null, null, null).andExpect(status().isBadRequest());
    resolve(admin, reportId, "WARN", null, "   ").andExpect(status().isBadRequest());
    resolve(admin, reportId, "WARN", null, "가".repeat(501)).andExpect(status().isBadRequest());
    assertThat(reportStatus(reportId)).isEqualTo("PENDING");
    assertThat(userSanctions(target, "WARNING")).isZero();

    resolve(admin, reportId, "NO_ISSUE", null, null).andExpect(status().isNoContent());
    assertThat(reportStatus(reportId)).isEqualTo("NO_ISSUE");
    assertThat(lastNotification(reporter, "REPORT_RESULT")).endsWith("문제 없음");
    assertThat(lastLogDetail(admin, "RESOLVE_REPORT")).isEqualTo("문제 없음");
    assertThat(notifications(target, "ACCOUNT_SANCTION")).isZero();

    JsonNode pending = body(call(admin, get("/api/admin/reports"), null)).get("items");
    for (JsonNode item : pending) {
      assertThat(item.get("id").asLong()).isNotEqualTo(reportId);
    }
    JsonNode done =
        find(body(call(admin, get("/api/admin/reports?status=DONE"), null)).get("items"), reportId);
    assertThat(done.get("status").asString()).isEqualTo("NO_ISSUE");
    assertThat(done.get("handledBy").asString()).isEqualTo(admin.getNickname());
    assertThat(done.get("resolutions").size()).isZero();
    assertThat(done.get("blogId").isNull()).isTrue();
  }

  @Test
  @DisplayName("ADM-08: 메인 프로필 신고를 경고로 처리하면 계정 경고를 남기고 대상과 신고자에게 알린다")
  void profileWarningRecordsAnAccountWarning() throws Exception {
    User admin = newAdmin();
    User reporter = newUser();
    User target = newUser();
    long reportId = reportId(reporter, "USER", target.getId(), null, "ADMIN");

    resolve(admin, reportId, "WARN", null, "  욕설 닉네임  ").andExpect(status().isNoContent());

    assertThat(reportStatus(reportId)).isEqualTo("WARNED");
    assertThat(
            jdbc.queryForObject(
                "select report_id from user_sanctions where user_id = ? and type = 'WARNING'",
                Long.class,
                target.getId()))
        .isEqualTo(reportId);
    assertThat(lastNotification(target, "ACCOUNT_SANCTION")).endsWith("사유: 욕설 닉네임");
    assertThat(lastNotification(reporter, "REPORT_RESULT")).endsWith("경고");
    assertThat(logs(admin, "ACCOUNT_WARN")).isEqualTo(1);
    assertThat(lastLogDetail(admin, "RESOLVE_REPORT")).isEqualTo("경고. 욕설 닉네임");
  }

  @Test
  @DisplayName("ADM-08: 메인 관리자 계정은 신고가 와도 제재할 수 없다(403)")
  void adminAccountsCannotBeSanctioned() throws Exception {
    User admin = newAdmin();
    User otherAdmin = newAdmin();
    long reportId = reportId(newUser(), "USER", otherAdmin.getId(), null, "ADMIN");

    resolve(admin, reportId, "ACCOUNT_SUSPEND", 3, "시험")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(containsString("관리자 계정")));
    assertThat(reportStatus(reportId)).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select suspended_until from users where id = ?",
                LocalDateTime.class,
                otherAdmin.getId()))
        .isNull();
    call(admin, post("/api/admin/users/" + otherAdmin.getId() + "/release"), null)
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("ADM-08: 계정 정지는 3·14·30일·영구만 고를 수 있고, 기간 정지는 끝나는 날짜를 알리며 해제하면 기록에 해제 시각이 남는다")
  void accountSuspensionPeriodsAndRelease() throws Exception {
    User admin = newAdmin();
    User target = newUser();
    long reportId = reportId(newUser(), "USER", target.getId(), null, "ADMIN");

    resolve(admin, reportId, "ACCOUNT_SUSPEND", 7, "도배").andExpect(status().isBadRequest());
    assertThat(reportStatus(reportId)).isEqualTo("PENDING");
    resolve(admin, reportId, "ACCOUNT_SUSPEND", 30, "도배").andExpect(status().isNoContent());

    assertThat(reportStatus(reportId)).isEqualTo("ACCOUNT_SUSPENDED");
    Map<String, Object> sanction =
        jdbc.queryForMap(
            "select suspend_days, ends_at, released_at from user_sanctions"
                + " where user_id = ? and type = 'SUSPENSION'",
            target.getId());
    assertThat(sanction.get("suspend_days")).isEqualTo(30);
    assertThat(sanction.get("released_at")).isNull();
    assertThat(lastNotification(target, "ACCOUNT_SANCTION")).contains("까지)").doesNotContain("영구");

    call(admin, post("/api/admin/users/" + target.getId() + "/release"), null)
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "select suspended_until from users where id = ?",
                LocalDateTime.class,
                target.getId()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select released_by from user_sanctions where user_id = ? and type = 'SUSPENSION'",
                Long.class,
                target.getId()))
        .isEqualTo(admin.getId());
    assertThat(lastNotification(target, "ACCOUNT_SANCTION")).isEqualTo("계정 정지가 풀렸어요.");
    assertThat(logs(admin, "ACCOUNT_RELEASE")).isEqualTo(1);

    // 탈퇴한 회원은 대상이 아니다
    User withdrawn = newUser();
    jdbc.update("update users set status = 'WITHDRAWN' where id = ?", withdrawn.getId());
    call(admin, post("/api/admin/users/" + withdrawn.getId() + "/release"), null)
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("ADM-08: 프로필 초기화는 사진 파일도 지우고, \"회원\"+번호를 다른 회원이 쓰고 있으면 초기화하지 않는다")
  void profileResetDeletesThePhotoAndRefusesATakenNickname() throws Exception {
    User admin = newAdmin();
    User target = newUser();
    String photo = storage.store(new ByteArrayInputStream(new byte[] {1, 2, 3}), "png");
    jdbc.update("update users set profile_image = ? where id = ?", photo, target.getId());
    long first = reportId(newUser(), "USER", target.getId(), null, "ADMIN");

    resolve(admin, first, "PROFILE_RESET", null, "부적절한 사진").andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "select profile_image from users where id = ?", String.class, target.getId()))
        .isNull();
    assertThat(storage.exists(photo)).isFalse();
    assertThat(lastNotification(target, "ACCOUNT_SANCTION")).contains("'회원" + target.getId() + "'");

    // 이미 초기화한 닉네임이면 다시 초기화할 수 있다
    long again = reportId(newUser(), "USER", target.getId(), null, "ADMIN");
    resolve(admin, again, "PROFILE_RESET", null, "또 신고").andExpect(status().isNoContent());

    User other = newUser();
    User squatter = newUser();
    jdbc.update(
        "update users set nickname = ? where id = ?", "회원" + other.getId(), squatter.getId());
    long blocked = reportId(newUser(), "USER", other.getId(), null, "ADMIN");
    resolve(admin, blocked, "PROFILE_RESET", null, "부적절한 닉네임").andExpect(status().isConflict());
    assertThat(reportStatus(blocked)).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select nickname from users where id = ?", String.class, other.getId()))
        .isEqualTo(other.getNickname());
  }

  @Test
  @DisplayName("ADM-04·ADM-07: 블로그 신고를 블로그장 경고로 처리하면 블로그장 경고가 쌓이고, 블로그 폐쇄는 7일 유예 폐쇄를 건다")
  void ownerReportWarnsTheOwnerOrClosesTheBlog() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner);
    long warnReport = reportId(reporter, "BLOG", blogId, null, "ADMIN");
    long closeReport = reportId(newUser(), "BLOG", blogId, null, "ADMIN");

    JsonNode row =
        find(body(call(admin, get("/api/admin/reports"), null)).get("items"), warnReport);
    assertThat(row.get("kind").asString()).isEqualTo("OWNER");
    assertThat(row.get("targetUserId").asLong()).isEqualTo(owner.getId());
    assertThat(row.get("resolutions").toString()).contains("OWNER_DEMOTE", "BLOG_CLOSE");

    resolve(admin, warnReport, "PROFILE_RESET", null, "안 됨").andExpect(status().isBadRequest());
    resolve(admin, warnReport, "WARN", null, "광고 블로그").andExpect(status().isNoContent());
    assertThat(reportStatus(warnReport)).isEqualTo("WARNED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from owner_sanctions where blog_id = ? and type = 'WARNING'",
                Integer.class,
                blogId))
        .isEqualTo(1);
    assertThat(role(blogId, owner)).isEqualTo("OWNER");

    resolve(admin, closeReport, "BLOG_CLOSE", null, "불법 정보").andExpect(status().isNoContent());
    assertThat(reportStatus(closeReport)).isEqualTo("BLOG_CLOSED");
    Map<String, Object> blog =
        jdbc.queryForMap("select status, close_reason from blogs where id = ?", blogId);
    assertThat(blog.get("status")).isEqualTo("CLOSING");
    assertThat(blog.get("close_reason")).isEqualTo("ADMIN");
    assertThat(logs(admin, "CLOSE_BLOG")).isEqualTo(1);
    assertThat(lastNotification(reporter, "REPORT_RESULT")).endsWith("경고");

    // 이미 폐쇄 예정이면 다시 폐쇄할 수 없고 신고는 대기 중으로 남는다
    long third = reportId(newUser(), "BLOG", blogId, null, "ADMIN");
    resolve(admin, third, "BLOG_CLOSE", null, "또").andExpect(status().isConflict());
    assertThat(reportStatus(third)).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("D-94: 블로그장이 쓴 글 신고를 블로그장 강퇴로 처리하면 부블로그장이 없으니 블로그가 폐쇄 예정이 된다")
  void ownerPostReportDemotesTheOwner() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    long blogId = createBlog(owner);
    long reportId =
        reportId(newUser(), "POST", writePost(owner, blogId, "블로그장 글"), blogId, "ADMIN");

    resolve(admin, reportId, "OWNER_DEMOTE", null, "반복 위반").andExpect(status().isNoContent());
    assertThat(reportStatus(reportId)).isEqualTo("OWNER_DEMOTED");
    assertThat(role(blogId, owner)).isEqualTo("MEMBER");
    assertThat(jdbc.queryForObject("select status from blogs where id = ?", String.class, blogId))
        .isEqualTo("CLOSING");
  }

  @Test
  @DisplayName("ADM-04: 신고 종류는 처리할 때의 블로그장 기준이라, 그사이 위임했으면 예전 블로그장의 글은 블로그 안 신고로 처리한다")
  void kindFollowsTheCurrentOwner() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    User successor = newUser();
    long blogId = createBlog(owner);
    join(successor, blogId);
    long reportId =
        reportId(newUser(), "POST", writePost(owner, blogId, "옛 블로그장 글"), blogId, "ADMIN");

    long transferId =
        body(call(
                    owner,
                    post("/api/blogs/" + blogId + "/transfers"),
                    "{\"toUserId\":" + successor.getId() + "}")
                .andExpect(status().isCreated()))
            .get("id")
            .asLong();
    call(successor, post("/api/transfers/" + transferId + "/accept"), null)
        .andExpect(status().isNoContent());

    JsonNode row = find(body(call(admin, get("/api/admin/reports"), null)).get("items"), reportId);
    assertThat(row.get("kind").asString()).isEqualTo("IN_BLOG");
    resolve(admin, reportId, "OWNER_DEMOTE", null, "안 됨").andExpect(status().isBadRequest());
    resolve(admin, reportId, "WARN", null, "광고").andExpect(status().isNoContent());

    assertThat(reportStatus(reportId)).isEqualTo("WARNED");
    assertThat(
            jdbc.queryForObject(
                "select report_id from member_sanctions where blog_id = ? and user_id = ?",
                Long.class,
                blogId,
                owner.getId()))
        .isEqualTo(reportId);
    assertThat(logs(admin, "MEMBER_WARN")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from owner_sanctions where blog_id = ?", Integer.class, blogId))
        .isZero();
  }

  @Test
  @DisplayName("D-100: 블로그장이 정지 중이고 부블로그장이 없을 때 들어온 블로그 안 신고는 관리자가 강제 퇴장까지 대신 처리한다")
  void adminKicksAMemberOnBehalfOfASuspendedOwner() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    User member = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    long commentId = writeComment(member, writePost(owner, blogId, "글"), "욕설 댓글");
    jdbc.update(
        "update users set suspended_until = ? where id = ?",
        LocalDateTime.now().plusDays(3),
        owner.getId());
    long reportId = reportId(reporter, "COMMENT", commentId, blogId, "ADMIN");

    JsonNode row = find(body(call(admin, get("/api/admin/reports"), null)).get("items"), reportId);
    assertThat(row.get("kind").asString()).isEqualTo("IN_BLOG");
    assertThat(row.get("resolutions").toString()).contains("KICK").doesNotContain("BLOG_CLOSE");
    resolve(admin, reportId, "KICK", null, "욕설").andExpect(status().isNoContent());

    assertThat(reportStatus(reportId)).isEqualTo("KICKED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from blog_members where blog_id = ? and user_id = ?",
                Integer.class,
                blogId,
                member.getId()))
        .isZero();
    assertThat(logs(admin, "MEMBER_KICK")).isEqualTo(1);
    assertThat(notifications(member, "MEMBER_SANCTION")).isEqualTo(1);
    assertThat(lastNotification(reporter, "REPORT_RESULT")).endsWith("강제 퇴장");
  }

  @Test
  @DisplayName("D-85: 신고된 블로그장 댓글이 정리돼 사라지면 제재는 못 하고 문제 없음으로만 닫는다")
  void reportWhoseTargetWasPurgedCanOnlyBeClosedAsNoIssue() throws Exception {
    User admin = newAdmin();
    User owner = newUser();
    long blogId = createBlog(owner);
    long commentId = writeComment(owner, writePost(owner, blogId, "글"), "사라질 댓글");
    long reportId = reportId(newUser(), "COMMENT", commentId, blogId, "ADMIN");
    jdbc.update("delete from comments where id = ?", commentId);

    resolve(admin, reportId, "WARN", null, "광고")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("문제 없음으로")));
    assertThat(reportStatus(reportId)).isEqualTo("PENDING");
    resolve(admin, reportId, "NO_ISSUE", null, null).andExpect(status().isNoContent());
  }

  // ---------- 도우미 ----------

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

  private long reportId(
      User reporter, String targetType, long targetId, Long blogId, String expectedScope)
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
    assertThat(created.get("handlerScope").asString()).isEqualTo(expectedScope);
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

  private String reportStatus(long reportId) {
    return jdbc.queryForObject("select status from reports where id = ?", String.class, reportId);
  }

  private String role(long blogId, User user) {
    return jdbc.queryForObject(
        "select role from blog_members where blog_id = ? and user_id = ?",
        String.class,
        blogId,
        user.getId());
  }

  private int userSanctions(User user, String type) {
    return jdbc.queryForObject(
        "select count(*) from user_sanctions where user_id = ? and type = ?",
        Integer.class,
        user.getId(),
        type);
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

  private String lastLogDetail(User admin, String action) {
    return jdbc.queryForObject(
        "select reason from admin_action_logs where admin_id = ? and action = ?"
            + " order by id desc limit 1",
        String.class,
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
    body.put("slug", "ar-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "관리자 신고 시험");
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
            "ar" + unique + "@example.com",
            "hash",
            "이름",
            "ar" + unique,
            "010" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000),
            LocalDateTime.now()));
  }
}
