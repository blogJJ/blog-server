package com.blog.social.api;

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
 * 신고 접수와 블로그 안 신고 처리를 실제 API로 확인한다 (T075, T080, SOC-06, BLG-13, D-45, D-85, D-94, D-95, D-100,
 * D-114).
 */
@IntegrationTest
class ReportFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;

  // ---------- 접수 ----------

  @Test
  @DisplayName("SOC-06: 대상·사유는 꼭 고르고, 자세한 내용은 500자까지이며 앞뒤 공백만이면 비워서 저장한다")
  void reportNeedsTargetAndReasonAndKeepsDetailWithinFiveHundredChars() throws Exception {
    User owner = newUser();
    User member = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(member, blogId);
    long postId = writePost(member, blogId, "광고 글");

    call(reporter, post("/api/reports"), report("POST", postId, null, null, null))
        .andExpect(status().isBadRequest());
    call(reporter, post("/api/reports"), report(null, postId, null, "SPAM", null))
        .andExpect(status().isBadRequest());
    call(reporter, post("/api/reports"), report("POST", postId, null, "SPAM", "가".repeat(501)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("500자")));

    // 500자는 이모지처럼 두 글자로 저장되는 문자도 한 글자로 센다
    long reportId =
        created(
            call(
                reporter,
                post("/api/reports"),
                report("POST", postId, null, "SPAM", "😀".repeat(500))));
    assertThat(column(reportId, "char_length(detail)", Integer.class)).isEqualTo(500);

    long blank =
        created(
            call(
                newUser(), post("/api/reports"), report("POST", postId, null, "ABUSE", "   \n  ")));
    assertThat(column(blank, "detail", String.class)).isNull();
    // 신고 당시 내용을 함께 남긴다 (D-85)
    assertThat(column(blank, "target_snapshot", String.class)).startsWith("[글] 광고 글\n");
  }

  @Test
  @DisplayName("D-85: 신고 당시 내용은 DB처럼 글자 수로 세어 1000자까지 남기고, 이모지를 반으로 자르지 않는다")
  void snapshotCountsCharactersLikeTheDatabase() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    // 이모지는 Java 문자열에서 두 칸이라, 길이로 자르면 1000칸째에서 이모지가 갈려 "?"로 저장됐다.
    // "[블로그] 넷글자다\n" + 이모지 500개는 511자라 자를 필요가 없다
    jdbc.update(
        "update blogs set name = '넷글자다', description = ? where id = ?", "😀".repeat(500), blogId);

    long reportId =
        created(call(newUser(), post("/api/reports"), report("BLOG", blogId, null, "SPAM", null)));
    String snapshot = column(reportId, "target_snapshot", String.class);
    assertThat(snapshot).isEqualTo("[블로그] 넷글자다\n" + "😀".repeat(500)).doesNotContain("?");
  }

  @Test
  @DisplayName("SOC-06: 자기 자신, 자기 글·댓글, 자기 블로그는 신고할 수 없다")
  void cannotReportYourselfOrYourOwnContent() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(member, blogId);
    long postId = writePost(member, blogId, "내 글");
    long commentId = writeComment(member, postId, "내 댓글");

    call(member, post("/api/reports"), report("POST", postId, blogId, "SPAM", null))
        .andExpect(status().isBadRequest());
    call(member, post("/api/reports"), report("COMMENT", commentId, blogId, "SPAM", null))
        .andExpect(status().isBadRequest());
    call(member, post("/api/reports"), report("USER", member.getId(), null, "SPAM", null))
        .andExpect(status().isBadRequest());
    call(owner, post("/api/reports"), report("BLOG", blogId, null, "SPAM", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("자기 자신")));
    assertThat(reportsBy(member) + reportsBy(owner)).isZero();
  }

  @Test
  @DisplayName("SOC-06: 숨김·삭제된 글과 댓글, 숨긴 글의 댓글, 탈퇴한 회원, 없는 대상은 신고할 수 없다(404)")
  void cannotReportContentThatIsNoLongerShown() throws Exception {
    User owner = newUser();
    User member = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(member, blogId);
    long hiddenPost = writePost(member, blogId, "숨긴 글");
    long commentOnHidden = writeComment(owner, hiddenPost, "숨긴 글의 댓글");
    jdbc.update("update posts set status = 'HIDDEN' where id = ?", hiddenPost);
    long livePost = writePost(member, blogId, "살아 있는 글");
    long deletedComment = writeComment(member, livePost, "지운 댓글");
    jdbc.update(
        "update comments set status = 'DELETED', deleted_at = now() where id = ?", deletedComment);
    User withdrawn = newUser();
    jdbc.update("update users set status = 'WITHDRAWN' where id = ?", withdrawn.getId());

    call(reporter, post("/api/reports"), report("POST", hiddenPost, blogId, "SPAM", null))
        .andExpect(status().isNotFound());
    call(reporter, post("/api/reports"), report("COMMENT", commentOnHidden, blogId, "SPAM", null))
        .andExpect(status().isNotFound());
    call(reporter, post("/api/reports"), report("COMMENT", deletedComment, blogId, "SPAM", null))
        .andExpect(status().isNotFound());
    call(reporter, post("/api/reports"), report("USER", withdrawn.getId(), null, "SPAM", null))
        .andExpect(status().isNotFound());
    call(reporter, post("/api/reports"), report("BLOG", Long.MAX_VALUE, null, "SPAM", null))
        .andExpect(status().isNotFound());
    call(
            reporter,
            post("/api/reports"),
            report("USER", member.getId(), Long.MAX_VALUE, "SPAM", null))
        .andExpect(status().isNotFound());
    assertThat(reportsBy(reporter)).isZero();
  }

  @Test
  @DisplayName("SOC-06: 볼 수 없는 블로그(비공개·관리자 숨김)의 대상은 신고할 수 없다")
  void cannotReportInsideABlogYouCannotSee() throws Exception {
    User owner = newUser();
    User member = newUser();
    User outsider = newUser();
    long privateBlog = createBlog(owner, "PUBLIC");
    join(member, privateBlog);
    long postId = writePost(member, privateBlog, "멤버 전용 글");
    jdbc.update("update blogs set visibility = 'PRIVATE' where id = ?", privateBlog);

    call(outsider, post("/api/reports"), report("POST", postId, privateBlog, "SPAM", null))
        .andExpect(status().isForbidden());
    call(outsider, post("/api/reports"), report("BLOG", privateBlog, null, "SPAM", null))
        .andExpect(status().isForbidden());
    call(outsider, post("/api/reports"), report("USER", member.getId(), privateBlog, "SPAM", null))
        .andExpect(status().isForbidden());

    long hiddenBlog = createBlog(newUser(), "PUBLIC");
    jdbc.update("update blogs set is_hidden = true where id = ?", hiddenBlog);
    call(outsider, post("/api/reports"), report("BLOG", hiddenBlog, null, "SPAM", null))
        .andExpect(status().isNotFound());
    assertThat(reportsBy(outsider)).isZero();
  }

  @Test
  @DisplayName("SOC-06: 같은 사람은 같은 대상을 2주에 한 번만 신고하고, 2주가 지나면 다시 신고할 수 있다")
  void sameTargetOnlyOncePerTwoWeeksPerReporter() throws Exception {
    User owner = newUser();
    User member = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(member, blogId);
    long commentId = writeComment(member, writePost(owner, blogId, "글"), "도배 댓글");

    long first =
        created(
            call(
                reporter,
                post("/api/reports"),
                report("COMMENT", commentId, blogId, "SPAM", null)));
    call(reporter, post("/api/reports"), report("COMMENT", commentId, blogId, "ABUSE", "다른 사유"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("2주")));
    // 다른 사람은 같은 대상을 신고할 수 있다
    created(
        call(newUser(), post("/api/reports"), report("COMMENT", commentId, blogId, "SPAM", null)));

    jdbc.update(
        "update reports set created_at = ? where id = ?", LocalDateTime.now().minusDays(13), first);
    call(reporter, post("/api/reports"), report("COMMENT", commentId, blogId, "SPAM", null))
        .andExpect(status().isConflict());
    jdbc.update(
        "update reports set created_at = ? where id = ?", LocalDateTime.now().minusDays(15), first);
    created(
        call(reporter, post("/api/reports"), report("COMMENT", commentId, blogId, "SPAM", null)));
    assertThat(reportsBy(reporter)).isEqualTo(2);
  }

  @Test
  @DisplayName("D-45·D-94·D-95: 블로그 안 멤버 신고는 블로그장, 블로그·블로그장 댓글·메인 프로필 신고는 메인 관리자가 처리한다")
  void handlerDependsOnWhatIsReported() throws Exception {
    User owner = newUser();
    User member = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(member, blogId);
    long postId = writePost(owner, blogId, "블로그장 글");
    long ownerComment = writeComment(owner, postId, "블로그장 댓글");
    long memberComment = writeComment(member, postId, "멤버 댓글");

    assertThat(scope(reporter, "BLOG", blogId, null)).isEqualTo("ADMIN");
    assertThat(scope(reporter, "COMMENT", ownerComment, blogId)).isEqualTo("ADMIN");
    assertThat(scope(reporter, "COMMENT", memberComment, blogId)).isEqualTo("BLOG_OWNER");
    assertThat(scope(reporter, "USER", member.getId(), null)).isEqualTo("ADMIN");
    assertThat(scope(newUser(), "USER", member.getId(), blogId)).isEqualTo("BLOG_OWNER");
    assertThat(scope(newUser(), "USER", owner.getId(), blogId)).isEqualTo("ADMIN");

    // 블로그 안에서 한 회원 신고는 그 블로그 멤버만 대상이다
    call(reporter, post("/api/reports"), report("USER", newUser().getId(), blogId, "SPAM", null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("멤버가 아니에요")));
  }

  @Test
  @DisplayName("D-100·D-114: 블로그장이 계정 정지 중이면 부블로그장이 없을 때만 블로그 안 신고가 메인 관리자에게 간다")
  void suspendedOwnerSendsInBlogReportsToAdminOnlyWithoutManagers() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(member, blogId);
    long first = writePost(member, blogId, "첫 글");
    long second = writePost(member, blogId, "둘째 글");
    jdbc.update(
        "update users set suspended_until = ? where id = ?",
        LocalDateTime.now().plusDays(3),
        owner.getId());

    assertThat(scope(newUser(), "POST", first, blogId)).isEqualTo("ADMIN");

    // 정지 중엔 참여 신청이 멈추므로(D-115) 정지를 풀고 부블로그장을 세운 뒤 다시 정지한다
    jdbc.update("update users set suspended_until = null where id = ?", owner.getId());
    User manager = newUser();
    join(manager, blogId);
    call(
            owner,
            put("/api/blogs/" + blogId + "/managers/" + manager.getId()),
            "{\"manager\":true,\"permissions\":[\"EDIT_INFO\"]}")
        .andExpect(status().isNoContent());
    jdbc.update(
        "update users set suspended_until = ? where id = ?",
        LocalDateTime.now().plusDays(3),
        owner.getId());

    assertThat(scope(newUser(), "POST", second, blogId)).isEqualTo("BLOG_OWNER");
    // 정지 동안 부블로그장은 받은 체크박스와 관계없이 신고를 처리한다 (D-114)
    call(manager, get("/api/blogs/" + blogId + "/reports"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].targetId").value(second))
        .andExpect(jsonPath("$[0].targetUserId").value(member.getId()));
  }

  // ---------- 블로그 안 처리 ----------

  @Test
  @DisplayName("BLG-13: 블로그장의 신고 목록엔 블로그장이 처리할 대기 중 신고만 나오고, 문제 없음으로 처리하면 제재 없이 신고자에게 알린다")
  void ownerSeesOnlyOwnPendingReportsAndNoIssueOnlyNotifies() throws Exception {
    User owner = newUser();
    User member = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(member, blogId);
    long memberPost = writePost(member, blogId, "멤버 글");
    long ownerPost = writePost(owner, blogId, "블로그장 글");
    long reportId = reportId(reporter, "POST", memberPost, blogId);
    reportId(reporter, "POST", ownerPost, blogId); // 메인 관리자 몫

    JsonNode pending =
        body(
            call(owner, get("/api/blogs/" + blogId + "/reports"), null).andExpect(status().isOk()));
    assertThat(pending.size()).isEqualTo(1);
    assertThat(pending.get(0).get("id").asLong()).isEqualTo(reportId);
    assertThat(pending.get(0).get("reporter").asString()).isEqualTo(reporter.getNickname());
    assertThat(pending.get(0).get("snapshot").asString()).contains("멤버 글");

    resolveInBlog(owner, blogId, reportId, "{}").andExpect(status().isBadRequest());
    resolveInBlog(owner, blogId, reportId, "{\"resolution\":\"NO_ISSUE\"}")
        .andExpect(status().isNoContent());

    assertThat(column(reportId, "status", String.class)).isEqualTo("NO_ISSUE");
    assertThat(column(reportId, "handled_by", Long.class)).isEqualTo(owner.getId());
    assertThat(sanctions(member)).isZero();
    assertThat(notifications(member, "MEMBER_SANCTION")).isZero();
    assertThat(notificationMessage(reporter, "REPORT_RESULT")).endsWith("문제 없음");
    call(owner, get("/api/blogs/" + blogId + "/reports"), null)
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("BLG-13: 정지·강제 퇴장으로 처리하면 그 블로그에서 제재하고 신고자에게 결과를 알린다")
  void suspendAndKickSanctionTheMemberInThatBlog() throws Exception {
    User owner = newUser();
    User suspended = newUser();
    User kicked = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(suspended, blogId);
    join(kicked, blogId);
    long suspendReport = reportId(reporter, "POST", writePost(suspended, blogId, "도배 1"), blogId);
    long kickReport = reportId(reporter, "USER", kicked.getId(), blogId);

    resolveInBlog(
            owner,
            blogId,
            suspendReport,
            "{\"resolution\":\"SUSPEND\",\"days\":7,\"reason\":\"도배\"}")
        .andExpect(status().isBadRequest());
    resolveInBlog(
            owner,
            blogId,
            suspendReport,
            "{\"resolution\":\"SUSPEND\",\"days\":3,\"reason\":\"도배\"}")
        .andExpect(status().isNoContent());
    resolveInBlog(owner, blogId, kickReport, "{\"resolution\":\"KICK\",\"reason\":\"욕설\"}")
        .andExpect(status().isNoContent());

    assertThat(column(suspendReport, "status", String.class)).isEqualTo("SUSPENDED");
    assertThat(column(kickReport, "status", String.class)).isEqualTo("KICKED");
    assertThat(
            jdbc.queryForObject(
                "select suspended_until > now() from blog_members where blog_id = ? and user_id = ?",
                Boolean.class,
                blogId,
                suspended.getId()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from blog_members where blog_id = ? and user_id = ?",
                Integer.class,
                blogId,
                kicked.getId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from blog_blacklist where blog_id = ? and user_id = ?",
                Integer.class,
                blogId,
                kicked.getId()))
        .isEqualTo(1);
    assertThat(notifications(reporter, "REPORT_RESULT")).isEqualTo(2);
    assertThat(notificationMessage(reporter, "REPORT_RESULT")).endsWith("강제 퇴장");
  }

  @Test
  @DisplayName("BLG-13: 멤버가 아닌 댓글 작성자는 신고로 경고만 줄 수 있고 강제 퇴장은 할 수 없다")
  void visitorCommentCanBeWarnedButNotKicked() throws Exception {
    User owner = newUser();
    User visitor = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long postId = writePost(owner, blogId, "글");
    long first = reportId(reporter, "COMMENT", writeComment(visitor, postId, "광고 1"), blogId);
    long second = reportId(reporter, "COMMENT", writeComment(visitor, postId, "광고 2"), blogId);

    resolveInBlog(owner, blogId, first, "{\"resolution\":\"KICK\",\"reason\":\"광고\"}")
        .andExpect(status().isNotFound());
    assertThat(column(first, "status", String.class)).isEqualTo("PENDING");
    resolveInBlog(owner, blogId, second, "{\"resolution\":\"WARN\",\"reason\":\"광고\"}")
        .andExpect(status().isNoContent());
    assertThat(column(second, "status", String.class)).isEqualTo("WARNED");
    assertThat(notifications(visitor, "MEMBER_SANCTION")).isEqualTo(1);
  }

  @Test
  @DisplayName("BLG-13: 다른 블로그의 신고나 메인 관리자 몫의 신고는 블로그 화면에서 처리할 수 없다(404)")
  void ownerCannotResolveReportsOutsideTheirScope() throws Exception {
    User owner = newUser();
    User otherOwner = newUser();
    User member = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long otherBlog = createBlog(otherOwner, "PUBLIC");
    join(member, blogId);
    long inBlog = reportId(reporter, "POST", writePost(member, blogId, "멤버 글"), blogId);
    long adminScope = reportId(reporter, "POST", writePost(owner, blogId, "블로그장 글"), blogId);
    String noIssue = "{\"resolution\":\"NO_ISSUE\"}";

    resolveInBlog(otherOwner, otherBlog, inBlog, noIssue).andExpect(status().isNotFound());
    resolveInBlog(owner, blogId, adminScope, noIssue).andExpect(status().isNotFound());
    resolveInBlog(owner, blogId, Long.MAX_VALUE, noIssue).andExpect(status().isNotFound());
    resolveInBlog(member, blogId, inBlog, noIssue).andExpect(status().isForbidden());
    assertThat(column(inBlog, "status", String.class)).isEqualTo("PENDING");
    assertThat(column(adminScope, "status", String.class)).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("BLG-13: 멤버 관리 권한을 받은 부블로그장도 나에 대한 신고는 처리할 수 없다")
  void managerCannotResolveAReportAboutThemselves() throws Exception {
    User owner = newUser();
    User manager = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    join(manager, blogId);
    call(
            owner,
            put("/api/blogs/" + blogId + "/managers/" + manager.getId()),
            "{\"manager\":true,\"permissions\":[\"MANAGE_MEMBERS\"]}")
        .andExpect(status().isNoContent());
    long reportId = reportId(reporter, "POST", writePost(manager, blogId, "부블로그장 글"), blogId);

    resolveInBlog(manager, blogId, reportId, "{\"resolution\":\"NO_ISSUE\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(containsString("다른 관리자")));
    assertThat(column(reportId, "status", String.class)).isEqualTo("PENDING");
    resolveInBlog(owner, blogId, reportId, "{\"resolution\":\"WARN\",\"reason\":\"광고\"}")
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("D-85: 신고된 댓글이 정리돼 사라지면 제재는 못 하고 문제 없음으로만 처리하며, 신고 당시 내용은 남아 있다")
  void reportWhoseTargetWasPurgedCanOnlyBeClosedAsNoIssue() throws Exception {
    User owner = newUser();
    User visitor = newUser();
    User reporter = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    long commentId = writeComment(visitor, writePost(owner, blogId, "글"), "사라질 댓글");
    long reportId = reportId(reporter, "COMMENT", commentId, blogId);
    jdbc.update("delete from comments where id = ?", commentId);

    call(owner, get("/api/blogs/" + blogId + "/reports"), null)
        .andExpect(jsonPath("$[0].targetUserId").doesNotExist())
        .andExpect(jsonPath("$[0].snapshot").value(containsString("사라질 댓글")));
    resolveInBlog(owner, blogId, reportId, "{\"resolution\":\"WARN\",\"reason\":\"광고\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("문제 없음으로")));
    resolveInBlog(owner, blogId, reportId, "{\"resolution\":\"NO_ISSUE\"}")
        .andExpect(status().isNoContent());
  }

  // ---------- 도우미 ----------

  private String report(
      String targetType, Long targetId, Long blogId, String reason, String detail) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetType", targetType);
    body.put("targetId", targetId);
    body.put("blogId", blogId);
    body.put("reason", reason);
    body.put("detail", detail);
    return jsonMapper.writeValueAsString(body);
  }

  private long reportId(User reporter, String targetType, long targetId, Long blogId)
      throws Exception {
    return created(
        call(reporter, post("/api/reports"), report(targetType, targetId, blogId, "SPAM", null)));
  }

  private String scope(User reporter, String targetType, long targetId, Long blogId)
      throws Exception {
    return body(call(
                reporter, post("/api/reports"), report(targetType, targetId, blogId, "SPAM", null))
            .andExpect(status().isCreated()))
        .get("handlerScope")
        .asString();
  }

  private long created(ResultActions result) throws Exception {
    return body(result.andExpect(status().isCreated())).get("id").asLong();
  }

  private ResultActions resolveInBlog(User user, long blogId, long reportId, String json)
      throws Exception {
    return call(user, post("/api/blogs/" + blogId + "/reports/" + reportId + "/resolve"), json);
  }

  private <T> T column(long reportId, String expression, Class<T> type) {
    return jdbc.queryForObject(
        "select " + expression + " from reports where id = ?", type, reportId);
  }

  private int reportsBy(User reporter) {
    return jdbc.queryForObject(
        "select count(*) from reports where reporter_id = ?", Integer.class, reporter.getId());
  }

  private int sanctions(User user) {
    return jdbc.queryForObject(
        "select count(*) from member_sanctions where user_id = ?", Integer.class, user.getId());
  }

  private int notifications(User receiver, String type) {
    return jdbc.queryForObject(
        "select count(*) from notifications where receiver_id = ? and type = ?",
        Integer.class,
        receiver.getId(),
        type);
  }

  private String notificationMessage(User receiver, String type) {
    return jdbc.queryForObject(
        "select message from notifications where receiver_id = ? and type = ?"
            + " order by id desc limit 1",
        String.class,
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

  private long createBlog(User owner, String visibility) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "r-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "신고 시험");
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

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "r" + unique + "@example.com",
            "hash",
            "이름",
            "r" + unique,
            "010" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000),
            LocalDateTime.now()));
  }
}
