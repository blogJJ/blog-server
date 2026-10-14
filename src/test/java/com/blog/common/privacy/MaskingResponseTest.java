package com.blog.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 다른 회원에게 보이는 응답 어디에도 이메일·전화번호 원문이 없다 (T147, SC-004, SEC-07, D-22). 블로그장이 보는 멤버 목록에는 가린 값만 나온다. */
@IntegrationTest
class MaskingResponseTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired UserRepository userRepository;

  @Test
  void otherMembersNeverSeeRawEmailOrPhone() throws Exception {
    User owner = newUser();
    User writer = newUser();
    User viewer = newUser();
    JsonNode blog = createBlog(owner);
    long blogId = blog.get("id").asLong();
    call(writer, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
    long postId = writePost(writer, blogId);
    call(writer, post("/api/posts/" + postId + "/comments"), "{\"content\":\"댓글\"}")
        .andExpect(status().isCreated());

    List<MockHttpServletRequestBuilder> asViewer =
        List.of(
            get("/api/users/" + writer.getId() + "/profile"),
            get("/api/blogs/by-slug/" + blog.get("slug").asString()),
            get("/api/blogs/" + blogId + "/posts"),
            get("/api/posts/" + postId),
            get("/api/posts/" + postId + "/comments"),
            get("/api/feed"),
            get("/api/search").param("q", writer.getNickname()).param("type", "post"),
            get("/api/search").param("q", "가리기").param("type", "blog"));
    for (MockHttpServletRequestBuilder request : asViewer) {
      String body = call(viewer, request, null).andReturn().getResponse().getContentAsString();
      assertThat(body).doesNotContain(writer.getEmail()).doesNotContain(digits(writer));
    }

    String members =
        call(owner, get("/api/blogs/" + blogId + "/members"), null)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(members)
        .doesNotContain(writer.getEmail())
        .doesNotContain(digits(writer))
        .contains(Masking.email(writer.getEmail()));
  }

  /** 하이픈 유무와 관계없이 전화번호 가운데·끝 8자리가 그대로 나오면 원문이다 */
  private static String digits(User user) {
    String phone = user.getPhone().replaceAll("\\D", "");
    return phone.substring(phone.length() - 8);
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

  private JsonNode createBlog(User owner) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "k-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "가리기 시험");
    body.put("visibility", "PUBLIC");
    body.put("joinPolicy", "OPEN");
    body.put("tags", List.of());
    String created =
        call(owner, post("/api/blogs"), jsonMapper.writeValueAsString(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return jsonMapper.readTree(created);
  }

  private long writePost(User user, long blogId) throws Exception {
    String body = "{\"title\":\"가리기 글\",\"content\":\"본문\",\"tags\":[],\"notice\":false}";
    String created =
        call(user, post("/api/blogs/" + blogId + "/posts"), body)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return jsonMapper.readTree(created).get("id").asLong();
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    String phone = "010" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
    return userRepository.save(
        new User(
            "k" + unique + "@example.com", "hash", "이름", "k" + unique, phone, LocalDateTime.now()));
  }
}
