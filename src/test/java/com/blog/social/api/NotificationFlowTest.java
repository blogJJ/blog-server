package com.blog.social.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.blog.common.security.AuthUser;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import com.blog.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** 알림함·읽음·전체 삭제·안 읽은 수·알림 설정을 실제 API로 확인한다 (T111~T113, SOC-04, 3.6). */
@IntegrationTest
class NotificationFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;
  @Autowired NotificationService notificationService;

  @Test
  void commentNotificationLinksToThePostAndCanBeRead() throws Exception {
    User writer = newUser();
    User reader = newUser();
    long blogId = createBlog(writer);
    long postId = writePost(writer, blogId);
    comment(reader, postId);

    call(writer, get("/api/notifications/unread-count"), null)
        .andExpect(jsonPath("$.count").value(1));
    JsonNode inbox = body(call(writer, get("/api/notifications"), null).andExpect(status().isOk()));
    JsonNode first = inbox.get("items").get(0);
    assertThat(first.get("type").asString()).isEqualTo("COMMENT");
    assertThat(first.get("tab").asString()).isEqualTo("COMMENT");
    assertThat(first.get("read").asBoolean()).isFalse();
    assertThat(first.get("actorNickname").asString()).isEqualTo(reader.getNickname());
    assertThat(first.get("link").asString())
        .isEqualTo("/blog/" + slugOf(blogId) + "/posts/" + postId);
    call(writer, get("/api/notifications?tab=LIKE"), null)
        .andExpect(jsonPath("$.items.length()").value(0));
    call(writer, get("/api/notifications?tab=NOPE"), null).andExpect(status().isBadRequest());

    long id = first.get("id").asLong();
    call(reader, post("/api/notifications/" + id + "/read"), null).andExpect(status().isNotFound());
    call(writer, post("/api/notifications/" + id + "/read"), null)
        .andExpect(status().isNoContent());
    call(writer, get("/api/notifications/unread-count"), null)
        .andExpect(jsonPath("$.count").value(0));
    call(writer, get("/api/notifications"), null)
        .andExpect(jsonPath("$.items[0].read").value(true));
  }

  @Test
  void turnedOffTypeStopsOnlyNewNotificationsAndRequiredOnesCannotBeTurnedOff() throws Exception {
    User writer = newUser();
    User reader = newUser();
    long blogId = createBlog(writer);
    long postId = writePost(writer, blogId);
    comment(reader, postId);

    JsonNode settings =
        body(call(writer, get("/api/me/notification-settings"), null).andExpect(status().isOk()));
    assertThat(settings.get("keepDays").asInt()).isEqualTo(30);
    List<String> types =
        settings.get("items").valueStream().map(s -> s.get("type").asString()).toList();
    assertThat(types).contains("COMMENT", "NOTICE").doesNotContain("BLOG_CLOSING", "POST_DELETED");

    call(writer, put("/api/me/notification-settings"), settingsBody(null, Map.of("COMMENT", false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.type == 'COMMENT')].enabled").value(false));
    comment(reader, postId);
    assertThat(count(writer)).isEqualTo(1);

    call(
            writer,
            put("/api/me/notification-settings"),
            settingsBody(null, Map.of("BLOG_CLOSING", false)))
        .andExpect(status().isBadRequest());
    notificationService.notify(
        writer.getId(), null, NotificationType.BLOG_CLOSING, null, null, "폐쇄 예정이에요");
    assertThat(count(writer)).isEqualTo(2);
  }

  @Test
  void keepDaysHidesOlderNotifications() throws Exception {
    User user = newUser();
    notificationService.notify(user.getId(), null, NotificationType.NOTICE, null, null, "새 공지");
    notificationService.notify(user.getId(), null, NotificationType.NOTICE, null, null, "옛 공지");
    jdbc.update(
        "update notifications set created_at = ? where receiver_id = ? and message = '옛 공지'",
        LocalDateTime.now().minusDays(10),
        user.getId());

    call(user, get("/api/notifications"), null).andExpect(jsonPath("$.totalElements").value(2));
    call(user, put("/api/me/notification-settings"), settingsBody(7, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keepDays").value(7));
    call(user, get("/api/notifications"), null)
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].message").value("새 공지"));
    call(user, get("/api/notifications/unread-count"), null)
        .andExpect(jsonPath("$.count").value(1));
    call(user, put("/api/me/notification-settings"), settingsBody(15, null))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteAllAndReadAllTouchOnlyMyNotifications() throws Exception {
    User user = newUser();
    User other = newUser();
    notificationService.notify(user.getId(), null, NotificationType.NOTICE, null, null, "공지");
    notificationService.notify(
        user.getId(), other.getId(), NotificationType.POST_LIKE, null, null, "좋아요");
    notificationService.notify(other.getId(), null, NotificationType.NOTICE, null, null, "남의 것");

    call(user, post("/api/notifications/read-all"), null).andExpect(status().isNoContent());
    call(user, get("/api/notifications/unread-count"), null)
        .andExpect(jsonPath("$.count").value(0));
    call(other, get("/api/notifications/unread-count"), null)
        .andExpect(jsonPath("$.count").value(1));

    call(user, delete("/api/notifications?tab=LIKE"), null)
        .andExpect(jsonPath("$.deleted").value(1));
    assertThat(count(user)).isEqualTo(1);
    call(user, delete("/api/notifications"), null).andExpect(jsonPath("$.deleted").value(1));
    assertThat(count(user)).isZero();
    assertThat(count(other)).isEqualTo(1);
  }

  @Test
  void guestsCannotReadNotifications() throws Exception {
    mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("알림을 누르면 종류에 맞는 화면으로 가고, 대상이 없어졌거나 갈 화면이 없으면 링크가 없다 (SOC-04, T093, T166)")
  void eachNotificationLinksToItsScreen() throws Exception {
    User owner = newUser();
    User receiver = newUser();
    long blogId = createBlog(owner);
    String slug = slugOf(blogId);
    long postId = writePost(owner, blogId);

    notify(receiver, NotificationType.OWNER_TRANSFER_REQUEST, NotificationTargetType.BLOG, blogId);
    notify(receiver, NotificationType.JOIN_REQUEST, NotificationTargetType.BLOG, blogId);
    notify(receiver, NotificationType.COMMENT_REVIEW, NotificationTargetType.BLOG, blogId);
    notify(receiver, NotificationType.MEMBER_SANCTION, NotificationTargetType.BLOG, blogId);
    notify(receiver, NotificationType.MEMBER_SANCTION, NotificationTargetType.BLOG, Long.MAX_VALUE);
    notify(receiver, NotificationType.FOLLOW, NotificationTargetType.USER, owner.getId());
    notify(receiver, NotificationType.NOTICE, NotificationTargetType.POST, postId);
    notify(receiver, NotificationType.POST_LIKE, NotificationTargetType.POST, postId);
    notify(receiver, NotificationType.POST_LIKE, NotificationTargetType.POST, Long.MAX_VALUE);
    notify(receiver, NotificationType.REPORT_RESULT, NotificationTargetType.REPORT, 1L);
    notify(receiver, NotificationType.NOTICE, null, null);
    notify(receiver, NotificationType.MEMBER_SANCTION, NotificationTargetType.BLOG, null);

    Map<String, String> links = new LinkedHashMap<>();
    for (JsonNode item : body(call(receiver, get("/api/notifications"), null)).get("items")) {
      String key = item.get("type").asString() + ":" + item.get("message").asString();
      links.put(key, item.get("link").isNull() ? null : item.get("link").asString());
    }
    String b = "BLOG:" + blogId;
    assertThat(links)
        .containsEntry("OWNER_TRANSFER_REQUEST:" + b, "/transfer.html")
        .containsEntry("JOIN_REQUEST:" + b, "/blog/" + slug + "/admin")
        .containsEntry("COMMENT_REVIEW:" + b, "/blog/" + slug + "/admin#comments")
        .containsEntry("MEMBER_SANCTION:" + b, "/blog/" + slug)
        .containsEntry("MEMBER_SANCTION:BLOG:" + Long.MAX_VALUE, null)
        .containsEntry("FOLLOW:USER:" + owner.getId(), "/profile.html?id=" + owner.getId())
        .containsEntry("NOTICE:POST:" + postId, "/notices.html?id=" + postId)
        .containsEntry("POST_LIKE:POST:" + postId, "/blog/" + slug + "/posts/" + postId)
        .containsEntry("POST_LIKE:POST:" + Long.MAX_VALUE, null)
        .containsEntry("REPORT_RESULT:REPORT:1", null)
        .containsEntry("NOTICE:-", null)
        .containsEntry("MEMBER_SANCTION:BLOG:null", null);
  }

  @Test
  @DisplayName("알림 설정 저장은 끌 수 있는 종류에 켬·끔 값이 있을 때만 되고, 하나라도 틀리면 보관 기간도 바뀌지 않는다 (SOC-04, 3.6)")
  void invalidSettingsChangeNothing() throws Exception {
    User user = newUser();

    Map<String, Boolean> missingValue = new LinkedHashMap<>();
    missingValue.put("COMMENT", null);
    call(user, put("/api/me/notification-settings"), settingsBody(7, missingValue))
        .andExpect(status().isBadRequest());
    call(user, put("/api/me/notification-settings"), settingsBody(7, Map.of("NOPE", true)))
        .andExpect(status().isBadRequest());
    call(user, get("/api/me/notification-settings"), null)
        .andExpect(jsonPath("$.keepDays").value(30))
        .andExpect(jsonPath("$.items[?(@.type == 'COMMENT')].enabled").value(true));

    // 7일로 줄였다가 30일로 되돌릴 수 있다
    call(user, put("/api/me/notification-settings"), settingsBody(7, null))
        .andExpect(jsonPath("$.keepDays").value(7));
    call(user, put("/api/me/notification-settings"), settingsBody(30, Map.of("FOLLOW", false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keepDays").value(30))
        .andExpect(jsonPath("$.items[?(@.type == 'FOLLOW')].enabled").value(false));
  }

  // ---------- 도우미 ----------

  /** 메시지에 대상을 적어 두어 목록에서 어느 알림인지 알아본다 */
  private void notify(
      User receiver, NotificationType type, NotificationTargetType target, Long id) {
    String message = target == null ? "-" : target.name() + ":" + id;
    notificationService.notify(receiver.getId(), null, type, target, id, message);
  }

  private int count(User user) {
    return jdbc.queryForObject(
        "select count(*) from notifications where receiver_id = ?", Integer.class, user.getId());
  }

  private String settingsBody(Integer keepDays, Map<String, Boolean> settings) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("keepDays", keepDays);
    body.put("settings", settings);
    return jsonMapper.writeValueAsString(body);
  }

  private void comment(User user, long postId) throws Exception {
    call(
            user,
            post("/api/posts/" + postId + "/comments"),
            jsonMapper.writeValueAsString(Map.of("content", "좋은 글이에요")))
        .andExpect(status().isCreated());
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
    body.put("slug", "n-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "알림 시험");
    body.put("visibility", "PUBLIC");
    body.put("joinPolicy", "OPEN");
    body.put("tags", List.of());
    return body(call(owner, post("/api/blogs"), jsonMapper.writeValueAsString(body))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private long writePost(User user, long blogId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "알림 글");
    body.put("content", "본문");
    body.put("tags", List.of());
    body.put("notice", false);
    return body(call(
                user, post("/api/blogs/" + blogId + "/posts"), jsonMapper.writeValueAsString(body))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private String slugOf(long blogId) {
    return jdbc.queryForObject("select slug from blogs where id = ?", String.class, blogId);
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "n" + unique + "@example.com",
            "hash",
            "이름",
            "n" + unique,
            "01012345678",
            LocalDateTime.now()));
  }
}
