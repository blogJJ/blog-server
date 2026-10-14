package com.blog.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
import com.blog.common.security.AuthUser;
import com.blog.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 04:00 배치를 고정 시계로 확인한다 (T085, SC-005, BLG-08, BLG-09, D-12, D-13, D-31, 4.5).
 *
 * <p>시계를 테스트가 정하므로 이 클래스는 다른 통합 테스트와 서버(컨텍스트)를 나눠 쓴다.
 */
@IntegrationTest
class DailyBatchIntegrationTest {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  /** 테스트가 시각을 옮기는 시계 */
  static class MutableClock extends Clock {

    private volatile Instant now = Instant.now();

    void set(LocalDateTime at) {
      now = at.atZone(SEOUL).toInstant();
    }

    @Override
    public ZoneId getZone() {
      return SEOUL;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @TestConfiguration
  static class ClockConfig {

    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;
  @Autowired MutableClock clock;
  @Autowired DailyBatchJob batch;

  @Test
  void closingSendsEachNoticeExactlyOnceAndClosesAtFourAm() throws Exception {
    User owner = newUser();
    User member = newUser();
    User subscriber = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    subscribe(subscriber, blogId);

    clock.set(LocalDateTime.of(2026, 10, 2, 15, 0));
    call(owner, post("/api/blogs/" + blogId + "/close"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSING"));
    assertThat(scheduledAt(blogId)).isEqualTo(LocalDateTime.of(2026, 10, 9, 4, 0));
    assertThat(closing(member)).isEqualTo(1);
    assertThat(closing(subscriber)).isEqualTo(1);
    assertThat(closing(owner)).isZero();

    runAt(2026, 10, 3);
    runAt(2026, 10, 5);
    assertThat(closing(member)).isEqualTo(1);

    runAt(2026, 10, 6);
    runAt(2026, 10, 6); // 같은 날 두 번 돌아도 한 번만
    assertThat(closing(member)).isEqualTo(2);
    assertThat(closing(subscriber)).isEqualTo(2);

    runAt(2026, 10, 7);
    runAt(2026, 10, 8);
    runAt(2026, 10, 8);
    assertThat(closing(member)).isEqualTo(3);
    assertThat(closing(subscriber)).isEqualTo(3);
    assertThat(blogStatus(blogId)).isEqualTo("CLOSING");

    // 폐쇄 예정 중에도 글쓰기는 평소처럼 (D-67)
    clock.set(LocalDateTime.of(2026, 10, 8, 12, 0));
    writePost(member, blogId);

    runAt(2026, 10, 9);
    assertThat(blogStatus(blogId)).isEqualTo("CLOSED");
    assertThat(
            jdbc.queryForObject(
                "select closed_at from blogs where id = ?", LocalDateTime.class, blogId))
        .isEqualTo(LocalDateTime.of(2026, 10, 9, 4, 0));
    assertThat(closing(member)).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from blog_close_notices where blog_id = ?", Integer.class, blogId))
        .isEqualTo(3);
  }

  @Test
  void missedDaysAreCaughtUpByTheNextBatch() throws Exception {
    User owner = newUser();
    User member = newUser();
    long late = createBlog(owner);
    long down = createBlog(owner);
    join(member, late);
    join(member, down);

    clock.set(LocalDateTime.of(2026, 10, 2, 15, 0));
    call(owner, post("/api/blogs/" + late + "/close")).andExpect(status().isOk());
    call(owner, post("/api/blogs/" + down + "/close")).andExpect(status().isOk());

    // 10월 6일 배치를 놓침 → 7일 배치가 3일 전 알림을 보낸다. 8일은 1일 전
    runAt(2026, 10, 7);
    assertThat(notices(late, "D3")).isEqualTo(1);
    runAt(2026, 10, 8);
    assertThat(notices(late, "D1")).isEqualTo(1);

    // 9일·10일 내내 꺼져 있었다가 11일에 켜짐 → 밀린 폐쇄를 처리
    clock.set(LocalDateTime.of(2026, 10, 9, 3, 59));
    assertThat(blogStatus(down)).isEqualTo("CLOSING");
    runAt(2026, 10, 11);
    assertThat(blogStatus(late)).isEqualTo("CLOSED");
    assertThat(blogStatus(down)).isEqualTo("CLOSED");
  }

  @Test
  void revokingStopsTheRemainingNotices() throws Exception {
    User owner = newUser();
    User member = newUser();
    User subscriber = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);
    subscribe(subscriber, blogId);

    clock.set(LocalDateTime.of(2026, 10, 2, 15, 0));
    call(owner, post("/api/blogs/" + blogId + "/close")).andExpect(status().isOk());
    call(member, post("/api/blogs/" + blogId + "/close/revoke")).andExpect(status().isForbidden());

    clock.set(LocalDateTime.of(2026, 10, 3, 10, 0));
    call(owner, post("/api/blogs/" + blogId + "/close/revoke"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    assertThat(count(member, "BLOG_CLOSE_CANCELED")).isEqualTo(1);
    assertThat(count(subscriber, "BLOG_CLOSE_CANCELED")).isEqualTo(1);

    runAt(2026, 10, 6);
    runAt(2026, 10, 8);
    runAt(2026, 10, 9);
    assertThat(closing(member)).isEqualTo(1);
    assertThat(blogStatus(blogId)).isEqualTo("ACTIVE");
    assertThat(scheduledAt(blogId)).isNull();
  }

  @Test
  void unansweredTransferExpiresAfterSevenDays() throws Exception {
    User owner = newUser();
    User member = newUser();
    long blogId = createBlog(owner);
    join(member, blogId);

    clock.set(LocalDateTime.of(2026, 10, 2, 15, 0));
    long transferId = requestTransfer(owner, blogId, member);
    assertThat(count(member, "OWNER_TRANSFER_REQUEST")).isEqualTo(1);

    runAt(2026, 10, 9);
    assertThat(transferStatus(transferId)).isEqualTo("PENDING");
    runAt(2026, 10, 10);
    assertThat(transferStatus(transferId)).isEqualTo("EXPIRED");
    assertThat(count(owner, "OWNER_TRANSFER_RESULT")).isEqualTo(1);
    runAt(2026, 10, 11);
    assertThat(count(owner, "OWNER_TRANSFER_RESULT")).isEqualTo(1);

    call(member, post("/api/transfers/" + transferId + "/accept")).andExpect(status().isConflict());
  }

  @Test
  void retentionCleanupRemovesExpiredData() throws Exception {
    LocalDateTime now = LocalDateTime.of(2026, 12, 1, 4, 0);
    clock.set(now.minusDays(40));
    User owner = newUser();
    User commenter = newUser();
    long blogId = createBlog(owner);
    long oldDeleted = writePost(owner, blogId);
    long newDeleted = writePost(owner, blogId);
    long kept = writePost(owner, blogId);
    jdbc.update(
        "insert into comments (post_id, author_id, content) values (?, ?, '부모')",
        oldDeleted,
        commenter.getId());
    Long parent =
        jdbc.queryForObject(
            "select id from comments where post_id = ? order by id desc limit 1",
            Long.class,
            oldDeleted);
    jdbc.update(
        "insert into comments (post_id, author_id, parent_id, content) values (?, ?, ?, '답글')",
        oldDeleted,
        commenter.getId(),
        parent);
    jdbc.update(
        "insert into post_likes (post_id, user_id) values (?, ?)", oldDeleted, commenter.getId());
    markDeleted(oldDeleted, now.minusDays(31));
    markDeleted(newDeleted, now.minusDays(10));

    long closedBlog = createBlog(owner);
    long closedPost = writePost(owner, closedBlog);
    jdbc.update("insert into categories (blog_id, name) values (?, '잡담')", closedBlog);
    jdbc.update(
        "update blogs set status = 'CLOSED', closed_at = ? where id = ?",
        now.minusDays(31),
        closedBlog);

    User withdrawn = newUser();
    jdbc.update(
        "update users set status = 'WITHDRAWN', email = null, withdrawn_at = ? where id = ?",
        now.minusDays(31),
        withdrawn.getId());
    User recent = newUser();
    jdbc.update(
        "update users set status = 'WITHDRAWN', email = null, withdrawn_at = ? where id = ?",
        now.minusDays(5),
        recent.getId());

    jdbc.update(
        "insert into refresh_tokens (user_id, token_hash, expires_at) values (?, ?, ?)",
        owner.getId(),
        "expired-" + UUID.randomUUID(),
        now.minusDays(1));
    jdbc.update(
        "insert into refresh_tokens (user_id, token_hash, expires_at) values (?, ?, ?)",
        owner.getId(),
        "alive-" + UUID.randomUUID(),
        now.plusDays(1));
    jdbc.update(
        "insert into admin_action_logs (admin_id, action, target_type, target_id, created_at)"
            + " values (?, 'CLOSE_BLOG', 'BLOG', ?, ?)",
        owner.getId(),
        blogId,
        now.minusYears(1).minusDays(1));
    jdbc.update(
        "insert into notifications (receiver_id, tab, type, message, created_at)"
            + " values (?, 'BLOG', 'NOTICE', '오래된 알림', ?)",
        owner.getId(),
        now.minusDays(31));

    clock.set(now);
    batch.run();

    assertThat(rows("posts where id = ?", oldDeleted)).isZero();
    assertThat(rows("comments where post_id = ?", oldDeleted)).isZero();
    assertThat(rows("post_likes where post_id = ?", oldDeleted)).isZero();
    assertThat(rows("posts where id = ?", newDeleted)).isEqualTo(1);
    assertThat(rows("posts where id = ?", kept)).isEqualTo(1);

    assertThat(rows("blogs where id = ? and slug is null", closedBlog)).isEqualTo(1);
    assertThat(rows("posts where id = ?", closedPost)).isZero();
    assertThat(rows("categories where blog_id = ?", closedBlog)).isZero();
    assertThat(rows("blogs where id = ? and slug is not null", blogId)).isEqualTo(1);

    assertThat(rows("users where id = ? and nickname is null and name is null", withdrawn.getId()))
        .isEqualTo(1);
    assertThat(rows("users where id = ? and nickname is not null", recent.getId())).isEqualTo(1);

    assertThat(rows("refresh_tokens where user_id = ?", owner.getId())).isEqualTo(1);
    assertThat(rows("admin_action_logs where admin_id = ?", owner.getId())).isZero();
    assertThat(rows("notifications where receiver_id = ? and type = 'NOTICE'", owner.getId()))
        .isZero();
  }

  @Test
  void retentionCleanupRemovesRemovedAndNeverAttachedImages() throws Exception {
    LocalDateTime now = LocalDateTime.of(2026, 12, 1, 4, 0);
    clock.set(now.minusDays(40));
    User owner = newUser();
    long blogId = createBlog(owner);
    String removed = upload(owner, now.minusDays(40));
    String kept = upload(owner, now.minusDays(40));
    String abandoned = upload(owner, now.minusDays(31));
    String fresh = upload(owner, now.minusDays(5));
    long postId = writePost(owner, blogId, image(removed) + "\n" + image(kept));

    clock.set(now.minusDays(31));
    Map<String, Object> edit = new LinkedHashMap<>();
    edit.put("title", "글");
    edit.put("content", image(kept));
    edit.put("tags", List.of());
    edit.put("notice", false);
    call(owner, put("/api/posts/" + postId), jsonMapper.writeValueAsString(edit))
        .andExpect(status().isOk());
    assertThat(rows("post_images where stored_name = ? and deleted_at is not null", removed))
        .isEqualTo(1);

    clock.set(now);
    batch.run();

    assertThat(rows("post_images where stored_name = ?", removed)).isZero();
    assertThat(rows("post_images where stored_name = ?", abandoned)).isZero();
    assertThat(rows("post_images where stored_name = ? and post_id = ?", kept, postId))
        .isEqualTo(1);
    assertThat(rows("post_images where stored_name = ?", fresh)).isEqualTo(1);
  }

  // ---------- 도우미 ----------

  private void runAt(int year, int month, int day) {
    clock.set(LocalDateTime.of(year, month, day, 4, 0));
    batch.run();
  }

  private LocalDateTime scheduledAt(long blogId) {
    return jdbc.queryForObject(
        "select close_scheduled_at from blogs where id = ?", LocalDateTime.class, blogId);
  }

  private String blogStatus(long blogId) {
    return jdbc.queryForObject("select status from blogs where id = ?", String.class, blogId);
  }

  private String transferStatus(long transferId) {
    return jdbc.queryForObject(
        "select status from blog_owner_transfers where id = ?", String.class, transferId);
  }

  private int closing(User receiver) {
    return count(receiver, "BLOG_CLOSING");
  }

  private int count(User receiver, String type) {
    return jdbc.queryForObject(
        "select count(*) from notifications where receiver_id = ? and type = ?",
        Integer.class,
        receiver.getId(),
        type);
  }

  private int notices(long blogId, String stage) {
    return jdbc.queryForObject(
        "select count(*) from blog_close_notices where blog_id = ? and stage = ?",
        Integer.class,
        blogId,
        stage);
  }

  private int rows(String from, Object... args) {
    return jdbc.queryForObject("select count(*) from " + from, Integer.class, args);
  }

  private void markDeleted(long postId, LocalDateTime at) {
    jdbc.update("update posts set status = 'DELETED', deleted_at = ? where id = ?", at, postId);
  }

  private void subscribe(User user, long blogId) {
    jdbc.update(
        "insert into blog_subscriptions (blog_id, user_id) values (?, ?)", blogId, user.getId());
  }

  private long requestTransfer(User owner, long blogId, User to) throws Exception {
    String body = jsonMapper.writeValueAsString(Map.of("toUserId", to.getId()));
    return jsonMapper
        .readTree(
            call(owner, post("/api/blogs/" + blogId + "/transfers"), body)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("id")
        .asLong();
  }

  private ResultActions call(User user, MockHttpServletRequestBuilder request) throws Exception {
    return call(user, request, null);
  }

  private ResultActions call(User user, MockHttpServletRequestBuilder request, String json)
      throws Exception {
    request.with(csrf());
    request.with(
        authentication(
            UsernamePasswordAuthenticationToken.authenticated(
                new AuthUser(user.getId(), UserRole.USER),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")))));
    if (json != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
    return mockMvc.perform(request);
  }

  private long createBlog(User owner) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "b-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "배치 시험");
    body.put("visibility", "PUBLIC");
    body.put("joinPolicy", "OPEN");
    body.put("tags", List.of());
    return jsonMapper
        .readTree(
            call(owner, post("/api/blogs"), jsonMapper.writeValueAsString(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("id")
        .asLong();
  }

  private void join(User user, long blogId) throws Exception {
    call(user, post("/api/blogs/" + blogId + "/join")).andExpect(status().isOk());
  }

  /** 글을 저장하기 전에 올린 이미지 행. 파일은 없어도 정리 배치가 조용히 넘어간다 */
  private String upload(User user, LocalDateTime at) {
    String name = UUID.randomUUID() + ".webp";
    jdbc.update(
        "insert into post_images (uploader_id, stored_name, original_name, content_type,"
            + " size_bytes, created_at) values (?, ?, 'a.png', 'image/webp', 10, ?)",
        user.getId(),
        name,
        at);
    return name;
  }

  private static String image(String storedName) {
    return "![](/api/images/" + storedName + ")";
  }

  private long writePost(User user, long blogId, String content) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "글");
    body.put("content", content);
    body.put("tags", List.of());
    body.put("notice", false);
    return jsonMapper
        .readTree(
            call(user, post("/api/blogs/" + blogId + "/posts"), jsonMapper.writeValueAsString(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("id")
        .asLong();
  }

  private long writePost(User user, long blogId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "글");
    body.put("content", "본문");
    body.put("tags", List.of());
    body.put("notice", false);
    return jsonMapper
        .readTree(
            call(user, post("/api/blogs/" + blogId + "/posts"), jsonMapper.writeValueAsString(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("id")
        .asLong();
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    String phone = "010" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
    return userRepository.save(
        new User(
            "d" + unique + "@example.com", "hash", "이름", "d" + unique, phone, LocalDateTime.now()));
  }
}
