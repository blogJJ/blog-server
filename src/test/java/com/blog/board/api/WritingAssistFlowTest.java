package com.blog.board.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
import com.blog.board.service.ClaudeWritingClient;
import com.blog.board.service.ClaudeWritingClient.SpellingFix;
import com.blog.common.security.AuthUser;
import com.blog.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 글쓰기 도우미 API: 권한, 회원별 횟수 제한, 키가 없을 때의 대신 동작 (T167). Claude 클라이언트는 가짜라 진짜 API는 부르지 않는다. 가짜의 기본값은
 * enabled() = false라 키가 없는 서버와 같다.
 */
@IntegrationTest
class WritingAssistFlowTest {

  private static final String BODY =
      "주말에 고양이랑 캠핑을 다녀왔어요. 고양이가 텐트 안에서 잠만 잤어요. 캠핑 장비는 가볍게 챙겼고 고양이 간식도 넉넉히 챙겼어요.";

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;
  @MockitoBean ClaudeWritingClient claude;

  @Test
  void configHidesSpellingWithoutKey() throws Exception {
    mockMvc
        .perform(get("/api/posts/assist/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tags").value(true))
        .andExpect(jsonPath("$.spelling").value(false));

    when(claude.enabled()).thenReturn(true);
    mockMvc.perform(get("/api/posts/assist/config")).andExpect(jsonPath("$.spelling").value(true));
  }

  @Test
  void onlyWritersOfThatBlogCanAsk() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner);

    mockMvc
        .perform(
            post("/api/posts/assist/tags")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(tagBody(blogId, "제목", BODY, List.of())))
        .andExpect(status().isUnauthorized());
    call(newUser(), post("/api/posts/assist/tags"), tagBody(blogId, "제목", BODY, List.of()))
        .andExpect(status().isForbidden());
    call(newUser(), post("/api/posts/assist/spelling"), spellingBody(blogId, BODY))
        .andExpect(status().isForbidden());
    call(owner, post("/api/posts/assist/tags"), tagBody(999_999_999L, "제목", BODY, List.of()))
        .andExpect(status().isNotFound());

    User member = newUser();
    call(member, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
    call(member, post("/api/posts/assist/tags"), tagBody(blogId, "제목", BODY, List.of()))
        .andExpect(status().isOk());
  }

  @Test
  void keywordTagsWithoutKeyPreferBlogTagsAndSkipAddedOnes() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner);
    // 이 블로그에서 쓰던 태그
    call(
            owner,
            post("/api/blogs/" + blogId + "/posts"),
            jsonMapper.writeValueAsString(
                Map.of("title", "지난 글", "content", "본문", "tags", List.of("캠핑"))))
        .andExpect(status().isCreated());

    call(owner, post("/api/posts/assist/tags"), tagBody(blogId, "고양이와 캠핑", BODY, List.of()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("keyword"))
        .andExpect(jsonPath("$.tags[0]").value("캠핑"))
        .andExpect(jsonPath("$.tags").value(Matchers.hasItem("고양이")));

    call(owner, post("/api/posts/assist/tags"), tagBody(blogId, "고양이와 캠핑", BODY, List.of("#캠핑")))
        .andExpect(jsonPath("$.tags").value(Matchers.not(Matchers.hasItem("캠핑"))));
    verify(claude, never()).suggestTags(anyString(), anyString(), anyList(), anyList());
  }

  @Test
  void aiTagsAreNormalizedAndFallBackWhenEmpty() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner);
    when(claude.enabled()).thenReturn(true);
    when(claude.suggestTags(anyString(), anyString(), anyList(), anyList()))
        .thenReturn(
            Optional.of(
                List.of(
                    "#Camping", "고양이 여행", "고양이 여행", "너무긴태그너무긴태그너무긴태그너무긴태그너무긴", "a-b", "이미단태그")));

    call(owner, post("/api/posts/assist/tags"), tagBody(blogId, "고양이와 캠핑", BODY, List.of("이미단태그")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("ai"))
        .andExpect(jsonPath("$.tags.length()").value(2))
        .andExpect(jsonPath("$.tags[0]").value("camping"))
        .andExpect(jsonPath("$.tags[1]").value("고양이_여행"));

    // 실패하면 단어 세기로
    when(claude.suggestTags(anyString(), anyString(), anyList(), anyList()))
        .thenReturn(Optional.empty());
    call(owner, post("/api/posts/assist/tags"), tagBody(blogId, "고양이와 캠핑", BODY, List.of()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("keyword"));
  }

  @Test
  void spellingNeedsKeyAndReturnsLocatedFixesOnly() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner);
    String text = "오늘은 날씨가 좋와서 산책을 했어요. 내일 뵈요.";

    call(owner, post("/api/posts/assist/spelling"), spellingBody(blogId, text))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ASSIST_UNAVAILABLE"));

    when(claude.enabled()).thenReturn(true);
    when(claude.checkSpelling(any()))
        .thenReturn(
            Optional.of(
                List.of(
                    new SpellingFix("좋와서", "좋아서", "'좋아서'가 맞아요."),
                    new SpellingFix("글에 없는 말", "고친 말", "없음"),
                    new SpellingFix("뵈요", "봬요", "'뵈어요'의 준말은 '봬요'예요."))));
    call(owner, post("/api/posts/assist/spelling"), spellingBody(blogId, text))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.fixes.length()").value(2))
        .andExpect(jsonPath("$.fixes[0].start").value(text.indexOf("좋와서")))
        .andExpect(jsonPath("$.fixes[0].suggestion").value("좋아서"))
        .andExpect(jsonPath("$.fixes[1].original").value("뵈요"));

    when(claude.checkSpelling(any())).thenReturn(Optional.empty());
    call(owner, post("/api/posts/assist/spelling"), spellingBody(blogId, "다른 글이에요."))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void perUserRateLimitGivesFriendly429() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner);
    for (int i = 0; i < 5; i++) {
      call(owner, post("/api/posts/assist/tags"), tagBody(blogId, "제목", BODY, List.of()))
          .andExpect(status().isOk());
    }
    call(owner, post("/api/posts/assist/tags"), tagBody(blogId, "제목", BODY, List.of()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.message").value(Matchers.containsString("1분에 5번")));

    // 다른 회원은 따로 센다
    User other = newUser();
    call(other, post("/api/blogs/" + blogId + "/join"), null).andExpect(status().isOk());
    call(other, post("/api/posts/assist/tags"), tagBody(blogId, "제목", BODY, List.of()))
        .andExpect(status().isOk());

    when(claude.enabled()).thenReturn(true);
    when(claude.checkSpelling(any())).thenReturn(Optional.of(List.of()));
    for (int i = 0; i < 3; i++) {
      call(owner, post("/api/posts/assist/spelling"), spellingBody(blogId, BODY))
          .andExpect(status().isOk());
    }
    call(owner, post("/api/posts/assist/spelling"), spellingBody(blogId, BODY))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.message").value(Matchers.containsString("맞춤법 검사는 1분에 3번")));
  }

  @Test
  void emptyTextIsRejectedWithoutCounting() throws Exception {
    User owner = newUser();
    long blogId = createBlog(owner);
    call(owner, post("/api/posts/assist/tags"), tagBody(blogId, " ", "  ", List.of()))
        .andExpect(status().isBadRequest());
    call(owner, post("/api/posts/assist/tags"), tagBody(null, "제목", BODY, List.of()))
        .andExpect(status().isBadRequest());
  }

  // ---------- 도우미 ----------

  private String tagBody(Long blogId, String title, String content, List<String> tags) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("blogId", blogId);
    body.put("title", title);
    body.put("content", content);
    body.put("tags", tags);
    return jsonMapper.writeValueAsString(body);
  }

  private String spellingBody(long blogId, String content) {
    return jsonMapper.writeValueAsString(Map.of("blogId", blogId, "content", content));
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

  private long createBlog(User owner) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slug", "wa-" + UUID.randomUUID().toString().substring(0, 12));
    body.put("name", "도우미 시험 블로그");
    body.put("visibility", "PUBLIC");
    body.put("joinPolicy", "OPEN");
    body.put("tags", List.of());
    String res =
        call(owner, post("/api/blogs"), jsonMapper.writeValueAsString(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return jsonMapper.readTree(res).get("id").asLong();
  }

  private User newUser() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "w" + unique + "@example.com",
            "hash",
            "이름",
            "w" + unique,
            "01012345678",
            LocalDateTime.now()));
  }
}
