package com.blog.board.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import com.blog.board.domain.CommentReviewReason;
import com.blog.board.service.ClaudeCommentFilterClient;
import com.blog.board.service.ClaudeCommentFilterClient.Result;
import com.blog.board.service.CommentModerationService;
import com.blog.board.service.CommentModerationService.CommentWritten;
import com.blog.common.security.AuthUser;
import com.blog.support.IntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 새 댓글 자동 검사와 블로그장 확인을 실제 API로 확인한다 (T166, D-133). Claude는 가짜(mock)로 바꿔 진짜 API를 부르지 않는다.
 *
 * <ul>
 *   <li>수상한 댓글은 다른 사람에게서 빠지고 쓴 사람에게는 그대로 보인다.
 *   <li>블로그장이 승인하면 다시 보이고, 삭제하면 지워진다. 승인한 댓글은 고친 내용이 수상할 때만 다시 숨긴다.
 *   <li>검사가 실패하면 그대로 보인다(fail-open). 블로그장 본인의 댓글은 검사하지 않는다.
 * </ul>
 */
@IntegrationTest
class CommentModerationFlowTest {

  private static final Result SUSPECT = new Result(true, CommentReviewReason.AD);

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;
  @Autowired CommentModerationService moderationService;
  @MockitoBean ClaudeCommentFilterClient claude;

  @BeforeEach
  void enableFakeClaude() {
    when(claude.enabled()).thenReturn(true);
    when(claude.check(anyString())).thenReturn(Optional.of(Result.OK));
  }

  @Test
  void suspectCommentIsHiddenFromOthersButAuthorSeesItAsNormal() throws Exception {
    User owner = newUser();
    User spammer = newUser();
    User reader = newUser();
    long blogId = createBlog(owner);
    long postId = writePost(owner, blogId);
    when(claude.check("싸게 팝니다")).thenReturn(Optional.of(SUSPECT));

    long id = comment(spammer, postId, "싸게 팝니다", null);
    awaitStatus(id, "HIDDEN");

    // 다른 사람: 비회원·회원·블로그장 모두 글 화면에서 보이지 않는다
    for (User viewer : new User[] {null, reader, owner}) {
      call(viewer, get("/api/posts/" + postId + "/comments"), null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items.length()").value(0));
    }
    assertThat(count("select comment_count from posts where id = ?", postId)).isZero();
    // 쓴 사람에게는 평소처럼 보이고, 검사 이야기는 응답 어디에도 없다
    String mine =
        call(spammer, get("/api/posts/" + postId + "/comments"), null)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].content").value("싸게 팝니다"))
            .andExpect(jsonPath("$.items[0].deleted").value(false))
            .andExpect(jsonPath("$.items[0].canEdit").value(true))
            .andExpect(jsonPath("$.items[0].canDelete").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(mine).doesNotContain("review").doesNotContain("HIDDEN").doesNotContain("AD");

    // 쓴 사람은 고치거나 자기 댓글에 답글을 달 수 있다. 고쳐도 계속 확인을 기다린다
    call(spammer, put("/api/comments/" + id), commentBody("싸게 팝니다!!", null))
        .andExpect(status().isOk());
    long reply = comment(spammer, postId, "답글", id);
    assertThat(string("select status from comments where id = ?", id)).isEqualTo("HIDDEN");
    call(reader, get("/api/posts/" + postId + "/comments"), null)
        .andExpect(jsonPath("$.items.length()").value(0));
    call(spammer, get("/api/posts/" + postId + "/comments"), null)
        .andExpect(jsonPath("$.items[0].content").value("싸게 팝니다!!"))
        .andExpect(jsonPath("$.items[0].replies[0].id").value(reply));
    // 다른 사람은 그 댓글에 답글을 달 수 없다
    call(reader, post("/api/posts/" + postId + "/comments"), commentBody("답", id))
        .andExpect(status().isBadRequest());

    // 블로그장은 알림을 받고 댓글 확인 목록에서 본다
    assertThat(notifications(owner, "COMMENT_REVIEW")).isEqualTo(1);
    assertThat(notifications(spammer, "COMMENT_REVIEW")).isZero();
    call(owner, get("/api/blogs/" + blogId + "/comment-reviews"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(id))
        .andExpect(jsonPath("$[0].reason").value("AD"))
        .andExpect(jsonPath("$[0].content").value("싸게 팝니다!!"))
        .andExpect(jsonPath("$[0].postId").value(postId));
    // 알림을 누르면 블로그 관리의 댓글 확인 탭으로 간다
    call(owner, get("/api/notifications?tab=BLOG"), null)
        .andExpect(
            jsonPath("$.items[0].link").value(org.hamcrest.Matchers.endsWith("/admin#comments")));
  }

  @Test
  void approvalShowsItAgainAndSticksUntilASuspectEdit() throws Exception {
    User owner = newUser();
    User author = newUser();
    User reader = newUser();
    long blogId = createBlog(owner);
    long postId = writePost(owner, blogId);
    when(claude.check("이 사람 번호는 010-0000-0000")).thenReturn(Optional.of(SUSPECT));
    long id = comment(author, postId, "이 사람 번호는 010-0000-0000", null);
    awaitStatus(id, "HIDDEN");

    call(owner, post("/api/blogs/" + blogId + "/comment-reviews/" + id + "/approve"), null)
        .andExpect(status().isNoContent());

    assertThat(string("select review_status from comments where id = ?", id)).isEqualTo("APPROVED");
    assertThat(count("select comment_count from posts where id = ?", postId)).isEqualTo(1);
    call(reader, get("/api/posts/" + postId + "/comments"), null)
        .andExpect(jsonPath("$.items[0].content").value("이 사람 번호는 010-0000-0000"));
    call(owner, get("/api/blogs/" + blogId + "/comment-reviews"), null)
        .andExpect(jsonPath("$.length()").value(0));
    // 두 번 처리할 수 없다
    call(owner, post("/api/blogs/" + blogId + "/comment-reviews/" + id + "/approve"), null)
        .andExpect(status().isNotFound());

    // 괜찮은 내용으로 고치면 그대로 보인다
    call(author, put("/api/comments/" + id), commentBody("번호는 지웠어요", null))
        .andExpect(status().isOk());
    verify(claude, timeout(5000)).check("번호는 지웠어요");
    awaitWorkerIdle();
    assertThat(string("select status from comments where id = ?", id)).isEqualTo("ACTIVE");

    // 고친 내용이 다시 수상하면 다시 숨긴다
    when(claude.check("다시 광고")).thenReturn(Optional.of(SUSPECT));
    call(author, put("/api/comments/" + id), commentBody("다시 광고", null)).andExpect(status().isOk());
    awaitStatus(id, "HIDDEN");
    assertThat(string("select review_status from comments where id = ?", id)).isEqualTo("PENDING");
    assertThat(count("select comment_count from posts where id = ?", postId)).isZero();
  }

  @Test
  void deleteFromReviewRemovesIt() throws Exception {
    User owner = newUser();
    User author = newUser();
    long blogId = createBlog(owner);
    long postId = writePost(owner, blogId);
    when(claude.check("욕설")).thenReturn(Optional.of(SUSPECT));
    long id = comment(author, postId, "욕설", null);
    awaitStatus(id, "HIDDEN");

    call(owner, delete("/api/blogs/" + blogId + "/comment-reviews/" + id), null)
        .andExpect(status().isNoContent());

    assertThat(string("select status from comments where id = ?", id)).isEqualTo("DELETED");
    assertThat(count("select comment_count from posts where id = ?", postId)).isZero();
    call(author, get("/api/posts/" + postId + "/comments"), null)
        .andExpect(jsonPath("$.items.length()").value(0));
    call(author, delete("/api/comments/" + id), null).andExpect(status().isNotFound());
  }

  @Test
  void authorDeletingOwnHeldCommentDoesNotCountTwice() throws Exception {
    User owner = newUser();
    User author = newUser();
    long blogId = createBlog(owner);
    long postId = writePost(owner, blogId);
    // 검사는 다른 스레드에서 돌므로 가짜 Claude는 댓글을 쓰기 전에 정해 둔다(검사 중에 when()을 부르면 엇갈릴 수 있음)
    when(claude.check("스팸")).thenReturn(Optional.of(SUSPECT));
    comment(newUser(), postId, "보통 댓글", null);
    verify(claude, timeout(5000)).check("보통 댓글");
    long id = comment(author, postId, "스팸", null);
    awaitStatus(id, "HIDDEN");
    assertThat(count("select comment_count from posts where id = ?", postId)).isEqualTo(1);

    call(author, delete("/api/comments/" + id), null).andExpect(status().isNoContent());

    assertThat(count("select comment_count from posts where id = ?", postId)).isEqualTo(1);
  }

  @Test
  void onlyPostManagersAndAdminsReview() throws Exception {
    User owner = newUser();
    User manager = newUser();
    User member = newUser();
    User author = newUser();
    long blogId = createBlog(owner);
    join(manager, blogId);
    join(member, blogId);
    call(
            owner,
            put("/api/blogs/" + blogId + "/managers/" + manager.getId()),
            "{\"manager\":true,\"permissions\":[\"MANAGE_POSTS\"]}")
        .andExpect(status().isNoContent());
    long postId = writePost(owner, blogId);
    when(claude.check("광고")).thenReturn(Optional.of(SUSPECT));
    long id = comment(author, postId, "광고", null);
    awaitStatus(id, "HIDDEN");

    String url = "/api/blogs/" + blogId + "/comment-reviews";
    call(member, get(url), null).andExpect(status().isForbidden());
    call(author, get(url), null).andExpect(status().isForbidden());
    call(author, post(url + "/" + id + "/approve"), null).andExpect(status().isForbidden());
    call(manager, get(url), null).andExpect(jsonPath("$.length()").value(1));
    assertThat(notifications(manager, "COMMENT_REVIEW")).isEqualTo(1);
    assertThat(notifications(member, "COMMENT_REVIEW")).isZero();
    // 메인 관리자도 처리한다 (D-122)
    call(newAdmin(), UserRole.ADMIN, post(url + "/" + id + "/approve"), null)
        .andExpect(status().isNoContent());
    // 다른 블로그 주소로는 처리할 수 없다
    long other = createBlog(owner);
    call(owner, post("/api/blogs/" + other + "/comment-reviews/" + id + "/approve"), null)
        .andExpect(status().isNotFound());
  }

  @Test
  void failureOrMissingAnswerKeepsTheCommentVisible() throws Exception {
    User owner = newUser();
    User author = newUser();
    long blogId = createBlog(owner);
    long postId = writePost(owner, blogId);
    when(claude.check("실패하는 댓글")).thenReturn(Optional.empty());

    long id = comment(author, postId, "실패하는 댓글", null);

    verify(claude, timeout(5000)).check("실패하는 댓글");
    awaitWorkerIdle();
    assertThat(string("select status from comments where id = ?", id)).isEqualTo("ACTIVE");
    assertThat(string("select review_status from comments where id = ?", id)).isNull();
    call(null, get("/api/posts/" + postId + "/comments"), null)
        .andExpect(jsonPath("$.items[0].content").value("실패하는 댓글"));
  }

  @Test
  void withoutKeyNothingIsChecked() throws Exception {
    when(claude.enabled()).thenReturn(false);
    User owner = newUser();
    long postId = writePost(owner, createBlog(owner));

    long id = comment(newUser(), postId, "키 없음", null);

    awaitWorkerIdle();
    verify(claude, never()).check(anyString());
    assertThat(string("select status from comments where id = ?", id)).isEqualTo("ACTIVE");
  }

  @Test
  void ownerManagerAndAdminCommentsAreNotChecked() throws Exception {
    User owner = newUser();
    User manager = newUser();
    long blogId = createBlog(owner);
    join(manager, blogId);
    call(
            owner,
            put("/api/blogs/" + blogId + "/managers/" + manager.getId()),
            "{\"manager\":true,\"permissions\":[]}")
        .andExpect(status().isNoContent());
    long postId = writePost(owner, blogId);

    comment(owner, postId, "블로그장 댓글", null);
    comment(manager, postId, "부블로그장 댓글", null);
    call(
            newAdmin(),
            UserRole.ADMIN,
            post("/api/posts/" + postId + "/comments"),
            commentBody("관리자 댓글", null))
        .andExpect(status().isCreated());
    comment(newUser(), postId, "회원 댓글", null);

    verify(claude, timeout(5000)).check("회원 댓글");
    verify(claude, never()).check("블로그장 댓글");
    verify(claude, never()).check("부블로그장 댓글");
    verify(claude, never()).check("관리자 댓글");
  }

  @Test
  void perUserLimitStopsCallingClaude() throws Exception {
    User owner = newUser();
    User author = newUser();
    long blogId = createBlog(owner);
    long postId = writePost(owner, blogId);
    long id = comment(author, postId, "자주 쓰는 회원", null);
    verify(claude, timeout(5000)).check("자주 쓰는 회원");

    for (int i = 0; i < 30; i++) {
      moderationService.review(new CommentWritten(id, blogId, author.getId()));
    }

    // 회원당 한 시간에 20번까지만 묻는다(처음 1번 포함)
    verify(claude, times(20)).check("자주 쓰는 회원");
    verify(claude, atMost(20)).check(anyString());
  }

  // ---------- 도우미 ----------

  /** 댓글 검사 작업 스레드가 받은 일을 모두 끝낼 때까지 기다린다. 숨기지 않았음을 확인할 때 쓴다. */
  private void awaitWorkerIdle() {
    Object target = AopTestUtils.getUltimateTargetObject(moderationService);
    ThreadPoolExecutor worker = (ThreadPoolExecutor) ReflectionTestUtils.getField(target, "worker");
    assertThat(worker).isNotNull();
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> worker.getCompletedTaskCount() == worker.getTaskCount());
  }

  private void awaitStatus(long commentId, String status) {
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> status.equals(string("select status from comments where id = ?", commentId)));
  }

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

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private String string(String sql, Object... args) {
    return jdbc.queryForObject(sql, String.class, args);
  }

  private int notifications(User receiver, String type) {
    return count(
        "select count(*) from notifications where receiver_id = ? and type = ?",
        receiver.getId(),
        type);
  }

  private long writePost(User user, long blogId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "글");
    body.put("content", "본문");
    body.put("tags", List.of());
    body.put("notice", false);
    return body(call(
                user, post("/api/blogs/" + blogId + "/posts"), jsonMapper.writeValueAsString(body))
            .andExpect(status().isCreated()))
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
    if (user != null) {
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

  private long createBlog(User owner) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "m-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "댓글 검사 블로그");
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

  private User newAdmin() {
    User admin = newUser();
    jdbc.update("update users set role = 'ADMIN' where id = ?", admin.getId());
    return admin;
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "m" + unique + "@example.com",
            "hash",
            "이름",
            "m" + unique,
            "01012345678",
            LocalDateTime.now()));
  }
}
