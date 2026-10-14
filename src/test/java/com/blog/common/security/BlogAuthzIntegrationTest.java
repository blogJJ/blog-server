package com.blog.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * A 블로그의 블로그장은 B 블로그를 관리하지 못한다 (T127, SC-002, BLG-09~13). BlogAuthzTest가 빈 단위로 보는 것을 실제 API 주소로
 * 확인한다.
 */
@IntegrationTest
class BlogAuthzIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;

  @Test
  void ownerOfOneBlogCannotManageAnotherBlog() throws Exception {
    User ownerA = newUser();
    User ownerB = newUser();
    User memberB = newUser();
    createBlog(ownerA);
    long blogB = createBlog(ownerB);
    call(memberB, post("/api/blogs/" + blogB + "/join"), null).andExpect(status().isOk());
    long postB = writePost(memberB, blogB);
    String members = "/api/blogs/" + blogB + "/members/" + memberB.getId();
    String reason = "{\"reason\":\"남의 블로그\",\"days\":30}";

    call(ownerA, get("/api/blogs/" + blogB + "/members"), null).andExpect(status().isForbidden());
    call(ownerA, post(members + "/warn"), reason).andExpect(status().isForbidden());
    call(ownerA, post(members + "/suspend"), reason).andExpect(status().isForbidden());
    call(ownerA, post(members + "/kick"), reason).andExpect(status().isForbidden());
    call(
            ownerA,
            put("/api/blogs/" + blogB + "/managers/" + memberB.getId()),
            "{\"manager\":true,\"permissions\":[\"MANAGE_MEMBERS\"]}")
        .andExpect(status().isForbidden());
    call(ownerA, put("/api/blogs/" + blogB), blogForm()).andExpect(status().isForbidden());
    call(ownerA, post("/api/blogs/" + blogB + "/share-link"), null)
        .andExpect(status().isForbidden());
    call(ownerA, get("/api/blogs/" + blogB + "/reports"), null).andExpect(status().isForbidden());
    call(ownerA, post("/api/blogs/" + blogB + "/close"), null).andExpect(status().isForbidden());
    call(
            ownerA,
            post("/api/blogs/" + blogB + "/transfers"),
            "{\"toUserId\":" + ownerA.getId() + "}")
        .andExpect(status().isForbidden());
    call(ownerA, delete("/api/posts/" + postB), null).andExpect(status().isForbidden());

    assertThat(
            jdbc.queryForObject(
                "select status from blogs where id = ?", String.class, blogB))
        .isEqualTo("ACTIVE");
    assertThat(jdbc.queryForObject("select status from posts where id = ?", String.class, postB))
        .isNotEqualTo("DELETED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from member_sanctions where user_id = ?",
                Integer.class,
                memberB.getId()))
        .isZero();
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

  private String blogForm() throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "z-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "권한 시험");
    body.put("visibility", "PUBLIC");
    body.put("joinPolicy", "OPEN");
    body.put("tags", List.of());
    return jsonMapper.writeValueAsString(body);
  }

  private long createBlog(User owner) throws Exception {
    String created =
        call(owner, post("/api/blogs"), blogForm())
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return jsonMapper.readTree(created).get("id").asLong();
  }

  private long writePost(User user, long blogId) throws Exception {
    String body =
        "{\"title\":\"멤버 글\",\"content\":\"본문\",\"tags\":[],\"notice\":false}";
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
            "z" + unique + "@example.com", "hash", "이름", "z" + unique, phone, LocalDateTime.now()));
  }
}
