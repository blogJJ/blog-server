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
import com.blog.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** 팔로우·구독·차단·프로필·비공개 전환 알림을 실제 API로 확인한다 (T101~T105, T109, SOC-01~03, SOC-05, D-36, D-50). */
@IntegrationTest
class SocialFlowTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;

  @Test
  void followTogglesNotifiesAndShowsInLists() throws Exception {
    User a = newUser();
    User b = newUser();

    call(a, post("/api/users/" + b.getId() + "/follow"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(true))
        .andExpect(jsonPath("$.followerCount").value(1));
    assertThat(notifications(b, "FOLLOW")).isEqualTo(1);

    call(null, get("/api/users/" + b.getId() + "/followers"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(a.getId()))
        .andExpect(jsonPath("$.items[0].nickname").value(a.getNickname()))
        .andExpect(jsonPath("$.items[0].email").doesNotExist());
    call(null, get("/api/users/" + a.getId() + "/followings"), null)
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].id").value(b.getId()));

    // 다시 누르면 취소. 알림은 새로 할 때만
    call(a, post("/api/users/" + b.getId() + "/follow"), null)
        .andExpect(jsonPath("$.following").value(false))
        .andExpect(jsonPath("$.followerCount").value(0));
    call(a, post("/api/users/" + b.getId() + "/follow"), null)
        .andExpect(jsonPath("$.following").value(true));
    assertThat(notifications(b, "FOLLOW")).isEqualTo(2);

    call(a, post("/api/users/" + a.getId() + "/follow"), null).andExpect(status().isBadRequest());
    call(null, post("/api/users/" + b.getId() + "/follow"), null)
        .andExpect(status().isUnauthorized());
    // 메인 관리자도 팔로우한다 (D-122)
    call(admin(), post("/api/users/" + b.getId() + "/follow"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(true));
  }

  @Test
  void profileShowsOnlyPublicFieldsAndPublicBlogs() throws Exception {
    User owner = newUser();
    User viewer = newUser();
    long publicBlog = createBlog(owner, "PUBLIC");
    createBlog(owner, "PRIVATE");
    long joined = createBlog(viewer, "PUBLIC");
    call(owner, post("/api/blogs/" + joined + "/join"), null).andExpect(status().isOk());
    call(viewer, post("/api/users/" + owner.getId() + "/follow"), null).andExpect(status().isOk());

    JsonNode profile =
        body(
            call(viewer, get("/api/users/" + owner.getId() + "/profile"), null)
                .andExpect(status().isOk()));
    assertThat(profile.get("nickname").asString()).isEqualTo(owner.getNickname());
    assertThat(profile.get("followerCount").asLong()).isEqualTo(1);
    assertThat(profile.has("email")).isFalse();
    assertThat(profile.has("name")).isFalse();
    assertThat(profile.has("phone")).isFalse();
    assertThat(profile.get("ownedBlogs").valueStream().map(n -> n.get("id").asLong()).toList())
        .containsExactly(publicBlog);
    assertThat(profile.get("joinedBlogs").valueStream().map(n -> n.get("id").asLong()).toList())
        .containsExactly(joined);
    assertThat(profile.get("viewer").get("following").asBoolean()).isTrue();
    assertThat(profile.get("viewer").get("me").asBoolean()).isFalse();

    call(null, get("/api/users/" + owner.getId() + "/profile"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewer.canFollow").value(false));
    call(null, get("/api/users/999999999/profile"), null).andExpect(status().isNotFound());
  }

  @Test
  void subscribeTogglesNotifiesOwnerAndLinkOnlyNeedsShareKey() throws Exception {
    User owner = newUser();
    User reader = newUser();
    long blogId = createBlog(owner, "PUBLIC");

    call(reader, post("/api/blogs/" + blogId + "/subscribe"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subscribed").value(true))
        .andExpect(jsonPath("$.subscriberCount").value(1));
    assertThat(notifications(owner, "BLOG_SUBSCRIBE")).isEqualTo(1);
    call(reader, get("/api/blogs/by-slug/" + slugOf(blogId)), null)
        .andExpect(jsonPath("$.viewer.subscribed").value(true))
        .andExpect(jsonPath("$.subscriberCount").value(1));
    call(reader, post("/api/blogs/" + blogId + "/subscribe"), null)
        .andExpect(jsonPath("$.subscribed").value(false))
        .andExpect(jsonPath("$.subscriberCount").value(0));

    // 일부 공개는 공유 링크로 들어온 회원만 (D-50)
    JsonNode linkOnly = body(call(owner, post("/api/blogs"), blogBody("LINK_ONLY")));
    long linkBlog = linkOnly.get("id").asLong();
    String key = linkOnly.get("shareKey").asString();
    call(reader, post("/api/blogs/" + linkBlog + "/subscribe"), null)
        .andExpect(status().isForbidden());
    call(reader, post("/api/blogs/" + linkBlog + "/subscribe?share=" + key), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subscribed").value(true));
  }

  @Test
  void blockUnfollowsBothWaysAndStopsNotificationsAndFollowing() throws Exception {
    User me = newUser();
    User other = newUser();
    long blogId = createBlog(me, "PUBLIC");
    long postId = writePost(me, blogId, "차단 시험 글");
    call(me, post("/api/users/" + other.getId() + "/follow"), null).andExpect(status().isOk());
    call(other, post("/api/users/" + me.getId() + "/follow"), null).andExpect(status().isOk());

    call(me, post("/api/users/" + other.getId() + "/block"), null)
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_follows where follower_id in (?, ?)"
                    + " and followee_id in (?, ?)",
                Integer.class,
                me.getId(),
                other.getId(),
                me.getId(),
                other.getId()))
        .isZero();
    call(other, post("/api/users/" + me.getId() + "/follow"), null)
        .andExpect(status().isForbidden());
    call(me, get("/api/users/" + other.getId() + "/profile"), null)
        .andExpect(jsonPath("$.viewer.blocking").value(true))
        .andExpect(jsonPath("$.viewer.canFollow").value(false));
    call(me, get("/api/me/blocks"), null).andExpect(jsonPath("$[0].userId").value(other.getId()));

    // 차단한 회원이 일으킨 알림(좋아요)은 만들지 않는다
    int before = notifications(me, "POST_LIKE");
    call(other, post("/api/posts/" + postId + "/like"), null).andExpect(status().isOk());
    assertThat(notifications(me, "POST_LIKE")).isEqualTo(before);

    call(me, delete("/api/users/" + other.getId() + "/block"), null)
        .andExpect(status().isNoContent());
    call(other, post("/api/users/" + me.getId() + "/follow"), null).andExpect(status().isOk());
  }

  @Test
  void blockedAuthorsPostsAndCommentsAreHiddenOutsideButVisibleToFellowMembers() throws Exception {
    User owner = newUser();
    User writer = newUser();
    User outsider = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    call(writer, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
    long hiddenPost = writePost(writer, blogId, "막힐 글");
    long ownerPost = writePost(owner, blogId, "남는 글");
    long rootId = comment(writer, ownerPost, null);
    comment(outsider, ownerPost, rootId);
    comment(writer, ownerPost, null);

    // 블로그 밖의 회원이 차단하면 그 블로그에서도 안 보인다
    call(outsider, post("/api/users/" + writer.getId() + "/block"), null)
        .andExpect(status().isNoContent());
    List<Long> ids = postIds(call(outsider, get("/api/blogs/" + blogId + "/posts"), null));
    assertThat(ids).contains(ownerPost).doesNotContain(hiddenPost);
    JsonNode comments = body(call(outsider, get("/api/posts/" + ownerPost + "/comments"), null));
    assertThat(comments.get("items").size()).isEqualTo(1); // 답글이 있는 첫 댓글만 자리를 남긴다
    JsonNode kept = comments.get("items").get(0);
    assertThat(kept.get("blocked").asBoolean()).isTrue();
    assertThat(kept.get("content").isNull()).isTrue();
    assertThat(kept.get("replies").size()).isEqualTo(1);

    // 같은 블로그 멤버끼리는 그 블로그 안의 글이 보인다 (D-36)
    call(owner, post("/api/users/" + writer.getId() + "/block"), null)
        .andExpect(status().isNoContent());
    assertThat(postIds(call(owner, get("/api/blogs/" + blogId + "/posts"), null)))
        .contains(hiddenPost, ownerPost);
    call(owner, get("/api/posts/" + ownerPost + "/comments"), null)
        .andExpect(jsonPath("$.items.length()").value(2));
  }

  @Test
  void joinRequestFromUserBlockedByOwnerIsRejected() throws Exception {
    User owner = newUser();
    User applicant = newUser();
    long approvalBlog = createBlog(owner, "PUBLIC", "APPROVAL");
    call(applicant, post("/api/blogs/" + approvalBlog + "/join"), null).andExpect(status().isOk());

    // 차단하면 대기 중 신청은 거절되고, 새 신청은 자동으로 거절된다 (SOC-05)
    call(owner, post("/api/users/" + applicant.getId() + "/block"), null)
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "select status from blog_join_requests where blog_id = ? and user_id = ?",
                String.class,
                approvalBlog,
                applicant.getId()))
        .isEqualTo("REJECTED");
    long openBlog = createBlog(owner, "PUBLIC");
    call(applicant, post("/api/blogs/" + openBlog + "/join"), null)
        .andExpect(status().isForbidden());
  }

  @Test
  void switchingToPrivateNotifiesSubscribersButKeepsSubscriptions() throws Exception {
    User owner = newUser();
    User reader = newUser();
    User member = newUser();
    long blogId = createBlog(owner, "PUBLIC");
    call(member, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
    call(reader, post("/api/blogs/" + blogId + "/subscribe"), null).andExpect(status().isOk());
    call(member, post("/api/blogs/" + blogId + "/subscribe"), null).andExpect(status().isOk());

    call(owner, put("/api/blogs/" + blogId), updateBody("PRIVATE")).andExpect(status().isOk());
    assertThat(notifications(reader, "BLOG_PRIVATE")).isEqualTo(1);
    assertThat(notifications(member, "BLOG_PRIVATE")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from blog_subscriptions where blog_id = ?", Integer.class, blogId))
        .isEqualTo(2);
    call(reader, get("/api/blogs/by-slug/" + slugOf(blogId)), null)
        .andExpect(status().isForbidden());

    // 이미 비공개면 다시 저장해도 알림이 또 가지 않는다
    call(owner, put("/api/blogs/" + blogId), updateBody("PRIVATE")).andExpect(status().isOk());
    assertThat(notifications(reader, "BLOG_PRIVATE")).isEqualTo(1);
    call(reader, get("/api/notifications?tab=BLOG"), null)
        .andExpect(jsonPath("$.items[0].link").value("/blog/" + slugOf(blogId)));
  }

  // ---------- 도우미 ----------

  private int notifications(User user, String type) {
    return jdbc.queryForObject(
        "select count(*) from notifications where receiver_id = ? and type = ?",
        Integer.class,
        user.getId(),
        type);
  }

  private List<Long> postIds(ResultActions result) throws Exception {
    return body(result.andExpect(status().isOk()))
        .get("page")
        .get("items")
        .valueStream()
        .map(n -> n.get("id").asLong())
        .toList();
  }

  private long comment(User user, long postId, Long parentId) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("content", "댓글이에요");
    body.put("parentId", parentId);
    return body(call(
                user,
                post("/api/posts/" + postId + "/comments"),
                jsonMapper.writeValueAsString(body))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
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

  private long createBlog(User owner, String visibility) throws Exception {
    return createBlog(owner, visibility, "OPEN");
  }

  private long createBlog(User owner, String visibility, String joinPolicy) throws Exception {
    return body(call(owner, post("/api/blogs"), blogBody(visibility, joinPolicy))
            .andExpect(status().isCreated()))
        .get("id")
        .asLong();
  }

  private String blogBody(String visibility) {
    return blogBody(visibility, "OPEN");
  }

  private String blogBody(String visibility, String joinPolicy) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "s-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "소셜 시험");
    body.put("visibility", visibility);
    body.put("joinPolicy", joinPolicy);
    body.put("tags", List.of());
    return jsonMapper.writeValueAsString(body);
  }

  private String updateBody(String visibility) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", "소셜 시험");
    body.put("description", "소개");
    body.put("visibility", visibility);
    body.put("joinPolicy", "OPEN");
    body.put("tags", List.of());
    return jsonMapper.writeValueAsString(body);
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

  private String slugOf(long blogId) {
    return jdbc.queryForObject("select slug from blogs where id = ?", String.class, blogId);
  }

  private User admin() {
    User admin = newUser();
    jdbc.update("update users set role = 'ADMIN' where id = ?", admin.getId());
    return userRepository.findById(admin.getId()).orElseThrow();
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "s" + unique + "@example.com",
            "hash",
            "이름",
            "s" + unique,
            "01012345678",
            LocalDateTime.now()));
  }
}
