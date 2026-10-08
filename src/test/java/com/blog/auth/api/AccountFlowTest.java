package com.blog.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.auth.service.TurnstileVerifier;
import com.blog.board.service.TestImages;
import com.blog.common.mail.MailService;
import com.blog.common.security.AuthCookies;
import com.blog.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 이메일 찾기·비밀번호 재설정·내 정보 수정을 실제 API와 쿠키로 확인한다 (T095~T098, USR-06~08, SEC-05, SC-003). */
@IntegrationTest
class AccountFlowTest {

  private static final String PASSWORD = "secret12!";
  private static final String NEW_PASSWORD = "changed34@";
  private static final Pattern CODE = Pattern.compile("인증번호: (\\d{6})");

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired Clock clock;

  @MockitoSpyBean MailService mailService;
  @MockitoBean TurnstileVerifier turnstileVerifier;

  String ip;

  @BeforeEach
  void setUp() {
    ip = "10.7." + (int) (Math.random() * 250) + "." + ((int) (Math.random() * 250) + 1);
  }

  // ---------- 비밀번호 찾기 (T096) ----------

  @Test
  void resetWithEmailCodeLogsOutEverywhereAndUnlocks() throws Exception {
    User user = newUser("홍길동", "01011112222");
    Cookie[] oldDevice = cookies(login(user.getEmail(), PASSWORD).andExpect(status().isOk()));
    jdbc.update(
        "update users set login_fail_count = 5, locked_until = ? where id = ?",
        LocalDateTime.now(clock).plusMinutes(5),
        user.getId());

    String code = requestCodeFor(map("email", user.getEmail()), user.getEmail());
    String wrong = code.equals("000000") ? "111111" : "000000";
    perform(post("/api/auth/password/reset"), reset("email", user.getEmail(), wrong, NEW_PASSWORD))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("4번 남음")));
    perform(post("/api/auth/password/reset"), reset("email", user.getEmail(), code, "short"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("8~15자")));
    assertThat(
            jdbc.queryForObject(
                "select fail_count from verification_codes where email = ? order by id desc limit 1",
                Integer.class,
                user.getEmail()))
        .isEqualTo(1);

    perform(post("/api/auth/password/reset"), reset("email", user.getEmail(), code, NEW_PASSWORD))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/me").cookie(oldDevice)).andExpect(status().isUnauthorized());
    login(user.getEmail(), PASSWORD).andExpect(status().isUnauthorized());
    login(user.getEmail(), NEW_PASSWORD).andExpect(status().isOk());
    perform(post("/api/auth/password/reset"), reset("email", user.getEmail(), code, "again56#"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownEmailGetsTheSameAnswerButNoMail() throws Exception {
    String nobody = "nobody-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    perform(post("/api/auth/password/code"), json(map("email", nobody)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value(containsString("가입된 이메일이면")));
    verify(mailService, never()).send(eq(nobody), anyString(), anyString());
    perform(post("/api/auth/password/code"), json(map("email", nobody)))
        .andExpect(status().isTooManyRequests());
  }

  // ---------- 이메일 찾기 (T097) ----------

  @Test
  void findEmailListsMaskedAccountsAndResetsWithTheToken() throws Exception {
    String phone = "010" + (10_000_000 + (int) (Math.random() * 89_999_999));
    User first = newUser("김찾기", phone);
    User second = newUser("김찾기", phone);
    User withdrawn = newUser("김찾기", phone);
    jdbc.update("update users set status = 'WITHDRAWN' where id = ?", withdrawn.getId());

    JsonNode found =
        body(
            perform(post("/api/auth/find-email"), json(map("name", "  김찾기 ", "phone", phone)))
                .andExpect(status().isOk()));
    assertThat(found.size()).isEqualTo(2);
    assertThat(found.get(0).get("email").asString()).contains("*").doesNotContain(first.getEmail());
    assertThat(found.get(0).get("joinedAt").isNull()).isFalse();
    String token = found.get(1).get("token").asString();

    String code = requestCodeFor(map("findToken", token), second.getEmail());
    perform(post("/api/auth/password/reset"), reset("findToken", token, code, NEW_PASSWORD))
        .andExpect(status().isOk());
    login(second.getEmail(), NEW_PASSWORD).andExpect(status().isOk());
    login(first.getEmail(), PASSWORD).andExpect(status().isOk());

    jdbc.update(
        "update account_find_tokens set expires_at = ? where user_id = ?",
        LocalDateTime.now(clock).minusMinutes(1),
        first.getId());
    String firstToken = found.get(0).get("token").asString();
    perform(post("/api/auth/password/code"), json(map("findToken", firstToken)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("이메일 찾기를 다시")));
  }

  @Test
  void findEmailIsLimitedToFivePerTenMinutes() throws Exception {
    for (int i = 0; i < 5; i++) {
      perform(post("/api/auth/find-email"), json(map("name", "없는사람", "phone", "01099998888")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    }
    perform(post("/api/auth/find-email"), json(map("name", "없는사람", "phone", "01099998888")))
        .andExpect(status().isTooManyRequests());
  }

  // ---------- 내 정보 (T098) ----------

  @Test
  void profileEditsOnlyWhatIsAllowed() throws Exception {
    User user = newUser("정보수정", "01033334444");
    User other = newUser("다른사람", "01055556666");
    Cookie[] me = cookies(login(user.getEmail(), PASSWORD).andExpect(status().isOk()));

    mockMvc
        .perform(get("/api/me").cookie(me))
        .andExpect(jsonPath("$.email").value(user.getEmail()))
        .andExpect(jsonPath("$.phone").value("01033334444"));

    Map<String, Object> form = new LinkedHashMap<>();
    form.put("nickname", "새닉네임" + (int) (Math.random() * 1000));
    form.put("phone", "010-7777-8888");
    form.put("bio", "  안녕하세요  ");
    form.put("name", "바뀐이름");
    form.put("email", "changed@example.com");
    withCookies(put("/api/me"), me, json(form))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nickname").value(form.get("nickname")))
        .andExpect(jsonPath("$.phone").value("01077778888"))
        .andExpect(jsonPath("$.bio").value("안녕하세요"))
        .andExpect(jsonPath("$.name").value("정보수정"))
        .andExpect(jsonPath("$.email").value(user.getEmail()));

    form.put("nickname", other.getNickname());
    withCookies(put("/api/me"), me, json(form)).andExpect(status().isConflict());
    form.put("nickname", "관리자임");
    withCookies(put("/api/me"), me, json(form)).andExpect(status().isBadRequest());
    form.put("nickname", "괜찮은닉" + (int) (Math.random() * 1000));
    form.put("bio", "가".repeat(201));
    withCookies(put("/api/me"), me, json(form)).andExpect(status().isBadRequest());
  }

  @Test
  void passwordChangeNeedsTheCurrentOneAndLogsOutOtherDevices() throws Exception {
    User user = newUser("비번변경", "01012121212");
    Cookie[] other = cookies(login(user.getEmail(), PASSWORD).andExpect(status().isOk()));
    Cookie[] here = cookies(login(user.getEmail(), PASSWORD).andExpect(status().isOk()));

    withCookies(
            put("/api/me/password"),
            here,
            json(map("currentPassword", "wrong12!", "newPassword", NEW_PASSWORD)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("현재 비밀번호가 맞지 않아요."));
    withCookies(
            put("/api/me/password"),
            here,
            json(map("currentPassword", PASSWORD, "newPassword", PASSWORD)))
        .andExpect(status().isBadRequest());

    MockHttpServletResponse changed =
        withCookies(
                put("/api/me/password"),
                here,
                json(map("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD)))
            .andExpect(status().isNoContent())
            .andReturn()
            .getResponse();

    mockMvc.perform(get("/api/me").cookie(other)).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/me").cookie(here)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/me").cookie(changed.getCookie(AuthCookies.ACCESS_TOKEN)))
        .andExpect(status().isOk());
    login(user.getEmail(), NEW_PASSWORD).andExpect(status().isOk());
  }

  @Test
  void profileImageCanBeReplacedAndDeleted() throws Exception {
    User user = newUser("사진", "01045454545");
    Cookie[] me = cookies(login(user.getEmail(), PASSWORD).andExpect(status().isOk()));

    String url =
        body(mockMvc
                .perform(
                    multipart(HttpMethod.PUT, "/api/me/profile-image")
                        .file(
                            new MockMultipartFile("file", "me.png", "image/png", TestImages.png()))
                        .cookie(me)
                        .with(csrf()))
                .andExpect(status().isOk()))
            .get("profileImage")
            .asString();
    mockMvc.perform(get(url)).andExpect(status().isOk());
    mockMvc.perform(get("/api/me").cookie(me)).andExpect(jsonPath("$.profileImage").value(url));

    withCookies(delete("/api/me/profile-image"), me, null).andExpect(status().isNoContent());
    mockMvc.perform(get(url)).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/me").cookie(me)).andExpect(jsonPath("$.profileImage").doesNotExist());
  }

  // ---------- 도우미 ----------

  /** 인증번호를 요청하고, 그 주소로 간 메일에서 번호를 꺼낸다 */
  private String requestCodeFor(Map<String, Object> target, String mailTo) throws Exception {
    clearInvocations(mailService);
    perform(post("/api/auth/password/code"), json(target)).andExpect(status().isOk());
    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(mailService, atLeastOnce()).send(eq(mailTo), anyString(), text.capture());
    Matcher m = CODE.matcher(text.getValue());
    assertThat(m.find()).isTrue();
    return m.group(1);
  }

  private String reset(String key, String value, String code, String newPassword) throws Exception {
    return json(map(key, value, "code", code, "newPassword", newPassword));
  }

  private ResultActions login(String email, String password) throws Exception {
    return perform(post("/api/auth/login"), json(map("email", email, "password", password)));
  }

  private static Cookie[] cookies(ResultActions result) {
    MockHttpServletResponse response = result.andReturn().getResponse();
    return new Cookie[] {
      response.getCookie(AuthCookies.ACCESS_TOKEN), response.getCookie(AuthCookies.REFRESH_TOKEN)
    };
  }

  private ResultActions withCookies(
      MockHttpServletRequestBuilder builder, Cookie[] cookies, String body) throws Exception {
    return perform(builder.cookie(cookies), body);
  }

  private ResultActions perform(MockHttpServletRequestBuilder builder, String body)
      throws Exception {
    builder
        .with(csrf())
        .with(
            request -> {
              request.setRemoteAddr(ip);
              return request;
            });
    if (body != null) {
      builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(builder);
  }

  private JsonNode body(ResultActions result) throws Exception {
    return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private static Map<String, Object> map(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }

  private String json(Map<String, Object> map) {
    return jsonMapper.writeValueAsString(map);
  }

  private User newUser(String name, String phone) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            "a" + unique + "@example.com",
            passwordEncoder.encode(PASSWORD),
            name,
            "a" + unique,
            phone,
            LocalDateTime.now(clock)));
  }
}
