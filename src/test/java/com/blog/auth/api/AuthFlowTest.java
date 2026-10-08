package com.blog.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.auth.service.ActivityPolicy;
import com.blog.auth.service.TurnstileVerifier;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.mail.MailService;
import com.blog.common.security.AuthCookies;
import com.blog.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가입·로그인·로그인 유지·로그아웃을 실제 API와 쿠키로 확인한다 (T028~T037, USR-01~04, SEC-03, SEC-04, SEC-13, SC-003).
 *
 * <p>IP별 횟수 제한에 걸리지 않게 테스트마다 다른 IP로 부르고, 시간이 지난 것은 DB의 시각을 당겨서 만든다.
 */
@IntegrationTest
class AuthFlowTest {

  private static final String PASSWORD = "secret12!";
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
  String email;

  @BeforeEach
  void setUp() {
    int n = (int) (Math.random() * 250) + 1;
    ip = "10.9." + (int) (Math.random() * 250) + "." + n;
    email = "u" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    when(turnstileVerifier.getSiteKey()).thenReturn("site-key");
  }

  // ---------- 가입 ----------

  @Test
  void signupWithEmailCodeLogsInRightAway() throws Exception {
    String code = requestCode(email);

    perform(post("/api/auth/signup/verify"), json("email", email, "code", code))
        .andExpect(status().isOk());
    MockHttpServletResponse response =
        perform(post("/api/auth/signup"), signupBody(email, nickname()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.nickname").exists())
            .andReturn()
            .getResponse();

    List<String> setCookies = response.getHeaders(HttpHeaders.SET_COOKIE);
    assertThat(setCookies)
        .anySatisfy(c -> assertThat(c).startsWith("access_token=").contains("HttpOnly"));
    assertThat(setCookies)
        .anySatisfy(
            c ->
                assertThat(c)
                    .startsWith("refresh_token=")
                    .contains("Path=/api/auth")
                    .contains("SameSite=Strict")
                    .doesNotContain("Max-Age"));
    User saved = userRepository.findByEmail(email).orElseThrow();
    assertThat(passwordEncoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
    assertThat(saved.getPasswordHash()).doesNotContain(PASSWORD);

    mockMvc
        .perform(get("/api/me").cookie(response.getCookie(AuthCookies.ACCESS_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(email));
  }

  @Test
  void signupWithoutVerifiedEmailIsRejected() throws Exception {
    requestCode(email);

    perform(post("/api/auth/signup"), signupBody(email, nickname()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
  }

  @Test
  void verifiedCodeWorksOnlyOnce() throws Exception {
    verifyEmail(email);
    perform(post("/api/auth/signup"), signupBody(email, nickname()))
        .andExpect(status().isCreated());

    String other = "x" + email;
    verifyEmail(other);
    jdbc.update(
        "update verification_codes set verified_at = ? where email = ?",
        LocalDateTime.now(clock).minusMinutes(31),
        other);
    perform(post("/api/auth/signup"), signupBody(other, nickname()))
        .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
  }

  @Test
  void resendIsLimitedToOncePerMinuteAndOldCodeDies() throws Exception {
    String first = requestCode(email);
    perform(post("/api/auth/signup/code"), json("email", email))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("CODE_RESEND_TOO_SOON"));

    jdbc.update(
        "update verification_codes set created_at = ? where email = ?",
        LocalDateTime.now(clock).minusMinutes(2),
        email);
    String second = requestCode(email);

    if (!first.equals(second)) {
      perform(post("/api/auth/signup/verify"), json("email", email, "code", first))
          .andExpect(jsonPath("$.code").value("CODE_INVALID"));
    }
    perform(post("/api/auth/signup/verify"), json("email", email, "code", second))
        .andExpect(status().isOk());
  }

  @Test
  void fiveWrongCodesKillTheCode() throws Exception {
    String code = requestCode(email);
    String wrong = code.equals("000000") ? "111111" : "000000";

    for (int left = 4; left >= 1; left--) {
      perform(post("/api/auth/signup/verify"), json("email", email, "code", wrong))
          .andExpect(jsonPath("$.code").value("CODE_INVALID"))
          .andExpect(jsonPath("$.message").value(containsString(left + "번 남음")));
    }
    perform(post("/api/auth/signup/verify"), json("email", email, "code", wrong))
        .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
    perform(post("/api/auth/signup/verify"), json("email", email, "code", code))
        .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
  }

  @Test
  void expiredCodeIsRejected() throws Exception {
    String code = requestCode(email);
    jdbc.update(
        "update verification_codes set expires_at = ? where email = ?",
        LocalDateTime.now(clock).minusSeconds(1),
        email);

    perform(post("/api/auth/signup/verify"), json("email", email, "code", code))
        .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
  }

  @Test
  void registeredEmailGetsSameScreenButDifferentMail() throws Exception {
    User existing = newUser(email);

    String body =
        perform(post("/api/auth/signup/code"), json("email", existing.getEmail()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String fresh =
        perform(post("/api/auth/signup/code"), json("email", "n" + email))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).isEqualTo(fresh);
    verify(mailService).send(eq(email), eq("[블로그] 이미 가입된 이메일이에요"), anyString());
    // 같은 1분 제한도 걸린다
    perform(post("/api/auth/signup/code"), json("email", email))
        .andExpect(jsonPath("$.code").value("CODE_RESEND_TOO_SOON"));
  }

  @Test
  void failedMailLeavesNoCodeSoUserCanRetryAtOnce() throws Exception {
    doThrow(new BusinessException(ErrorCode.MAIL_SEND_FAILED))
        .when(mailService)
        .send(eq(email), anyString(), anyString());
    perform(post("/api/auth/signup/code"), json("email", email))
        .andExpect(status().isServiceUnavailable());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from verification_codes where email = ?", Integer.class, email))
        .isZero();
  }

  @Test
  void duplicateNicknameAndBadInputAreRejected() throws Exception {
    User existing = newUser("o" + email);
    verifyEmail(email);

    perform(post("/api/auth/signup"), signupBody(email, existing.getNickname()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));

    String body =
        jsonMapper.writeValueAsString(
            java.util.Map.of(
                "email",
                email,
                "password",
                "short",
                "name",
                "이름",
                "nickname",
                nickname(),
                "phone",
                "01012345678",
                "agreeTerms",
                true,
                "agreePrivacy",
                true));
    perform(post("/api/auth/signup"), body).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  // ---------- 로그인 ----------

  @Test
  void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
    newUser(email);

    String wrong = login(email, "wrong12!", false).andReturn().getResponse().getContentAsString();
    String unknown =
        login("nobody" + email, PASSWORD, false).andReturn().getResponse().getContentAsString();

    assertThat(wrong).contains("LOGIN_FAILED");
    assertThat(unknown).isEqualTo(wrong);
  }

  @Test
  void withdrawnUserCannotLogIn() throws Exception {
    User user = newUser(email);
    jdbc.update("update users set status = 'WITHDRAWN' where id = ?", user.getId());

    login(email, PASSWORD, false).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
  }

  @Test
  void captchaAfterThreeFailuresAndLockAfterFive() throws Exception {
    newUser(email);
    login(email, "wrong12!", false).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    login(email, "wrong12!", false).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    login(email, "wrong12!", false).andExpect(jsonPath("$.code").value("CAPTCHA_REQUIRED"));

    // 사람 확인 없이 맞는 비밀번호를 넣어도 막힌다
    when(turnstileVerifier.verify(any(), any())).thenReturn(false);
    login(email, PASSWORD, false).andExpect(jsonPath("$.code").value("CAPTCHA_REQUIRED"));

    when(turnstileVerifier.verify(eq("ok"), any())).thenReturn(true);
    loginWithCaptcha(email, "wrong12!").andExpect(jsonPath("$.code").value("CAPTCHA_REQUIRED"));
    loginWithCaptcha(email, "wrong12!")
        .andExpect(status().isLocked())
        .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    loginWithCaptcha(email, PASSWORD).andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

    // 5분이 지나면 맞는 비밀번호(+사람 확인)로 들어가고 실패 횟수가 지워진다
    jdbc.update(
        "update users set locked_until = ? where email = ?",
        LocalDateTime.now(clock).minusSeconds(1),
        email);
    loginWithCaptcha(email, PASSWORD).andExpect(status().isOk());
    assertThat(userRepository.findByEmail(email).orElseThrow().getLoginFailCount()).isZero();
  }

  @Test
  void rememberMeKeepsCookiesFor14Days() throws Exception {
    newUser(email);

    List<String> cookies =
        login(email, PASSWORD, true)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeaders(HttpHeaders.SET_COOKIE);

    assertThat(cookies).hasSize(2).allSatisfy(c -> assertThat(c).contains("Max-Age=1209600"));
  }

  // ---------- 로그인 유지·로그아웃 ----------

  @Test
  void refreshIssuesNewAccessTokenUntilLogout() throws Exception {
    newUser(email);
    MockHttpServletResponse loggedIn = login(email, PASSWORD, false).andReturn().getResponse();
    Cookie access = loggedIn.getCookie(AuthCookies.ACCESS_TOKEN);
    Cookie refresh = loggedIn.getCookie(AuthCookies.REFRESH_TOKEN);

    MockHttpServletResponse refreshed =
        perform(post("/api/auth/refresh").cookie(refresh), null)
            .andExpect(status().isNoContent())
            .andReturn()
            .getResponse();
    assertThat(refreshed.getCookie(AuthCookies.ACCESS_TOKEN).getValue()).isNotBlank();

    perform(post("/api/auth/logout").cookie(access, refresh), null)
        .andExpect(status().isNoContent());

    // 로그아웃하면 아직 만료 전인 Access Token도 바로 못 쓴다
    mockMvc.perform(get("/api/me").cookie(access)).andExpect(status().isUnauthorized());
    perform(post("/api/auth/refresh").cookie(refresh), null).andExpect(status().isUnauthorized());
  }

  @Test
  void idleLoginEndsAfter30Minutes() throws Exception {
    newUser(email);
    MockHttpServletResponse loggedIn = login(email, PASSWORD, false).andReturn().getResponse();
    expireSessionsOf(email, LocalDateTime.now(clock).minusSeconds(1));

    mockMvc
        .perform(get("/api/me").cookie(loggedIn.getCookie(AuthCookies.ACCESS_TOKEN)))
        .andExpect(status().isUnauthorized());
    perform(post("/api/auth/refresh").cookie(loggedIn.getCookie(AuthCookies.REFRESH_TOKEN)), null)
        .andExpect(status().isUnauthorized());
  }

  @Test
  void onlyUserActionsExtendTheLogin() throws Exception {
    newUser(email);
    Cookie access =
        login(email, PASSWORD, false).andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
    LocalDateTime soon = LocalDateTime.now(clock).plusMinutes(10).withNano(0);
    expireSessionsOf(email, soon);

    mockMvc
        .perform(get("/api/me").cookie(access).header(ActivityPolicy.AUTO_REQUEST_HEADER, "true"))
        .andExpect(status().isOk());
    assertThat(sessionExpiryOf(email)).isEqualTo(soon);

    mockMvc
        .perform(
            get("/api/auth/session")
                .cookie(access)
                .header(ActivityPolicy.AUTO_REQUEST_HEADER, "true"))
        .andExpect(jsonPath("$.rememberMe").value(false))
        .andExpect(jsonPath("$.expiresAt").exists())
        .andExpect(jsonPath("$.expiresInSeconds").value(org.hamcrest.Matchers.lessThan(601)));
    assertThat(sessionExpiryOf(email)).isEqualTo(soon);

    mockMvc.perform(get("/api/me").cookie(access)).andExpect(status().isOk());
    assertThat(sessionExpiryOf(email)).isAfter(LocalDateTime.now(clock).plusMinutes(29));
  }

  @Test
  void extendButtonPushesExpiryBack() throws Exception {
    newUser(email);
    Cookie access =
        login(email, PASSWORD, false).andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
    expireSessionsOf(email, LocalDateTime.now(clock).plusMinutes(4));

    perform(
            post("/api/auth/extend")
                .cookie(access)
                .header(ActivityPolicy.AUTO_REQUEST_HEADER, "true"),
            null)
        .andExpect(status().isOk());

    assertThat(sessionExpiryOf(email)).isAfter(LocalDateTime.now(clock).plusMinutes(29));
  }

  @Test
  void rememberedLoginIsNotExtended() throws Exception {
    newUser(email);
    Cookie access =
        login(email, PASSWORD, true).andReturn().getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
    LocalDateTime before = sessionExpiryOf(email);

    mockMvc
        .perform(get("/api/auth/session").cookie(access))
        .andExpect(jsonPath("$.rememberMe").value(true));
    mockMvc.perform(get("/api/me").cookie(access)).andExpect(status().isOk());

    assertThat(sessionExpiryOf(email)).isEqualTo(before);
    assertThat(before).isAfter(LocalDateTime.now(clock).plusDays(13));
  }

  @Test
  void sessionInfoNeedsLogin() throws Exception {
    mockMvc.perform(get("/api/auth/session")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/auth/config"))
        .andExpect(jsonPath("$.turnstileSiteKey").value("site-key"));
  }

  // ---------- 도우미 ----------

  private org.springframework.test.web.servlet.ResultActions perform(
      MockHttpServletRequestBuilder builder, String body) throws Exception {
    builder.with(csrf()).with(fromIp());
    if (body != null) {
      builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(builder);
  }

  private RequestPostProcessor fromIp() {
    return request -> {
      request.setRemoteAddr(ip);
      return request;
    };
  }

  private String requestCode(String to) throws Exception {
    perform(post("/api/auth/signup/code"), json("email", to)).andExpect(status().isOk());
    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(mailService, org.mockito.Mockito.atLeastOnce())
        .send(eq(to), anyString(), text.capture());
    Matcher m = CODE.matcher(text.getValue());
    assertThat(m.find()).isTrue();
    return m.group(1);
  }

  private void verifyEmail(String to) throws Exception {
    perform(post("/api/auth/signup/verify"), json("email", to, "code", requestCode(to)))
        .andExpect(status().isOk());
  }

  private org.springframework.test.web.servlet.ResultActions login(
      String email, String password, boolean rememberMe) throws Exception {
    return perform(
        post("/api/auth/login"),
        jsonMapper.writeValueAsString(
            java.util.Map.of("email", email, "password", password, "rememberMe", rememberMe)));
  }

  private org.springframework.test.web.servlet.ResultActions loginWithCaptcha(
      String email, String password) throws Exception {
    return perform(
        post("/api/auth/login"),
        jsonMapper.writeValueAsString(
            java.util.Map.of("email", email, "password", password, "turnstileToken", "ok")));
  }

  private String json(String... pairs) throws Exception {
    java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return jsonMapper.writeValueAsString(map);
  }

  private String signupBody(String email, String nickname) throws Exception {
    return jsonMapper.writeValueAsString(
        java.util.Map.of(
            "email",
            email,
            "password",
            PASSWORD,
            "name",
            "홍길동",
            "nickname",
            nickname,
            "phone",
            "010-1234-5678",
            "agreeTerms",
            true,
            "agreePrivacy",
            true));
  }

  private static String nickname() {
    return "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private User newUser(String email) {
    return userRepository.save(
        new User(
            email,
            passwordEncoder.encode(PASSWORD),
            "이름",
            nickname(),
            "01012345678",
            LocalDateTime.now(clock)));
  }

  private void expireSessionsOf(String email, LocalDateTime at) {
    jdbc.update(
        "update refresh_tokens set expires_at = ? where user_id = (select id from users where email = ?)",
        at,
        email);
  }

  private LocalDateTime sessionExpiryOf(String email) {
    return jdbc.queryForObject(
        "select expires_at from refresh_tokens where user_id = (select id from users where email = ?)",
        LocalDateTime.class,
        email);
  }
}
