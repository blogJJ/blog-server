package com.blog.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.SocialAccount;
import com.blog.auth.domain.SocialProvider;
import com.blog.auth.domain.User;
import com.blog.auth.repository.SocialAccountRepository;
import com.blog.auth.repository.UserRepository;
import com.blog.auth.service.PendingSocialSignup;
import com.blog.auth.service.SocialLoginService;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.AuthCookies;
import com.blog.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 구글·깃허브 소셜 로그인 (T157, D-128). 제공자에는 실제로 다녀오지 않고, Spring Security가 제공자에게서 받아 만들었을 OAuth2User를 직접
 * 만들어 돌아온 뒤의 처리(SocialLoginHandlers)를 부른다. 구글만 켜고 깃허브는 키를 비워 둔다.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "app.social.google.client-id=test-google-client",
      "app.social.google.client-secret=test-google-secret",
      "app.social.github.client-id=",
      "app.social.github.client-secret="
    })
class SocialLoginFlowTest {

  private static final String PASSWORD = "secret12!";

  @Autowired MockMvc mockMvc;
  @Autowired JsonMapper jsonMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository userRepository;
  @Autowired SocialAccountRepository socialAccountRepository;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired SocialLoginHandlers handlers;
  @Autowired Clock clock;
  @Autowired SocialLoginService socialLoginService;

  String ip;
  String email;
  String sub;

  @BeforeEach
  void setUp() {
    ip =
        "10.8."
            + ThreadLocalRandom.current().nextInt(250)
            + "."
            + (ThreadLocalRandom.current().nextInt(250) + 1);
    email = "s" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    sub = "g-" + UUID.randomUUID();
  }

  // ---------- 켜진 제공자 ----------

  @Test
  void onlyConfiguredProviderIsEnabled() throws Exception {
    mockMvc
        .perform(get("/api/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.socialProviders.length()").value(1))
        .andExpect(jsonPath("$.socialProviders[0]").value("google"));

    // 구글은 state를 붙여 구글로 보내고, 돌아오는 주소는 /login/oauth2/code/google
    var start =
        mockMvc
            .perform(
                get("/oauth2/authorization/google")
                    .param("next", "/me.html")
                    .param("remember", "1"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    assertThat(start.getResponse().getRedirectedUrl())
        .startsWith("https://accounts.google.com/o/oauth2/v2/auth")
        .contains("client_id=test-google-client")
        .contains("state=")
        .contains("redirect_uri=http://localhost/login/oauth2/code/google");
    MockHttpSession session = (MockHttpSession) start.getRequest().getSession();
    assertThat(session.getAttribute(SocialSession.NEXT)).isEqualTo("/me.html");
    assertThat(session.getAttribute(SocialSession.REMEMBER_ME)).isEqualTo(true);

    // state가 맞지 않게 돌아오면 제공자를 부르지 않고 로그인 화면으로 돌려보낸다
    mockMvc
        .perform(
            get("/login/oauth2/code/google")
                .param("code", "fake")
                .param("state", "wrong")
                .session(session))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login.html?social_error=failed"));
    // 사용자가 구글 화면에서 취소한 경우(state는 맞음)
    String state =
        UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl())
            .build()
            .getQueryParams()
            .getFirst("state");
    mockMvc
        .perform(
            get("/login/oauth2/code/google")
                .param("error", "access_denied")
                .param("state", URLDecoder.decode(state, StandardCharsets.UTF_8))
                .session(session))
        .andExpect(redirectedUrl("/login.html?social_error=cancelled"));

    // 키가 없는 깃허브는 보내지 않고, 연결도 시작할 수 없다
    mockMvc.perform(get("/oauth2/authorization/github")).andExpect(status().isNotFound());
    Cookie cookie = accessTokenOf(passwordLogin(newUser(email)));
    perform(post("/api/me/social/github/link").cookie(cookie))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SOCIAL_PROVIDER_DISABLED"));
    perform(get("/api/me/social").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accounts.length()").value(1))
        .andExpect(jsonPath("$.accounts[0].provider").value("GOOGLE"));
  }

  @Test
  void nextOnlyAcceptsSameSitePaths() {
    assertThat(SocialSession.safePath("/blog/abc?x=1")).isEqualTo("/blog/abc?x=1");
    assertThat(SocialSession.safePath("//evil.com")).isEqualTo("/");
    assertThat(SocialSession.safePath("/\\evil.com")).isEqualTo("/");
    assertThat(SocialSession.safePath("https://evil.com")).isEqualTo("/");
    assertThat(SocialSession.safePath("/a\r\nSet-Cookie: x")).isEqualTo("/");
    assertThat(SocialSession.safePath(null)).isEqualTo("/");
  }

  // ---------- 기존 회원과 연결 ----------

  @Test
  void verifiedEmailLinksExistingUserAndLogsIn() throws Exception {
    User user = newUser(email);

    // 제공자가 대문자로 줘도 소문자로 맞춰 찾는다. 로그인 화면에서 넘긴 next로 간다
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(SocialSession.NEXT, "/blog/abc");
    MockHttpServletResponse response = callback(session, google(email.toUpperCase(), true, "홍길동"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/blog/abc");
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
        .anySatisfy(c -> assertThat(c).startsWith("access_token=").contains("HttpOnly"))
        .anySatisfy(c -> assertThat(c).startsWith("refresh_token=").contains("Path=/api/auth"));
    SocialAccount link =
        socialAccountRepository
            .findByUserIdAndProvider(user.getId(), SocialProvider.GOOGLE)
            .orElseThrow();
    assertThat(link.getProviderUserId()).isEqualTo(sub);
    assertThat(link.getEmailAtLink()).isEqualTo(email);
    mockMvc
        .perform(get("/api/me").cookie(response.getCookie(AuthCookies.ACCESS_TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(user.getId()))
        .andExpect(jsonPath("$.hasPassword").value(true));

    // 다음부터는 이메일이 바뀌어도 제공자 회원 번호로 같은 회원이 된다
    MockHttpServletResponse again =
        callback(new MockHttpSession(), google("changed-" + email, true, "홍길동"));
    assertThat(again.getRedirectedUrl()).isEqualTo("/");
    mockMvc
        .perform(get("/api/me").cookie(again.getCookie(AuthCookies.ACCESS_TOKEN)))
        .andExpect(jsonPath("$.id").value(user.getId()));
  }

  @Test
  void unverifiedEmailDoesNotLink() throws Exception {
    User user = newUser(email);

    MockHttpServletResponse response = callback(new MockHttpSession(), google(email, false, "홍길동"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/login.html?social_error=email_unverified");
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(socialAccountRepository.findByUserIdOrderByIdAsc(user.getId())).isEmpty();
  }

  @Test
  void lockedUserCannotLogInWithSocialEither() throws Exception {
    User user = newUser(email);
    jdbc.update(
        "update users set login_fail_count = 5, locked_until = ? where id = ?",
        LocalDateTime.now(clock).plusMinutes(5),
        user.getId());

    MockHttpServletResponse response = callback(new MockHttpSession(), google(email, true, "홍길동"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/login.html?social_error=locked");
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @Test
  void suspendedUserCanStillLogIn() throws Exception {
    User user = newUser(email);
    jdbc.update(
        "update users set suspended_until = ? where id = ?",
        LocalDateTime.now(clock).plusDays(3),
        user.getId());

    MockHttpServletResponse response = callback(new MockHttpSession(), google(email, true, "홍길동"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/");
    assertThat(response.getCookie(AuthCookies.ACCESS_TOKEN)).isNotNull();
  }

  // ---------- 새로 가입 ----------

  @Test
  void newPersonFinishesSignupAndIsLoggedIn() throws Exception {
    MockHttpSession session = new MockHttpSession();
    MockHttpServletResponse response = callback(session, google(email, true, "김소셜"));

    // 바로 만들지 않고 가입 마무리 화면으로
    assertThat(response.getRedirectedUrl()).isEqualTo("/social-signup.html");
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(userRepository.findByEmail(email)).isEmpty();
    perform(get("/api/auth/social/pending").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provider").value("GOOGLE"))
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.name").value("김소셜"))
        .andExpect(jsonPath("$.providerUserId").doesNotExist());

    // 약관에 동의하지 않으면 안 된다
    perform(
            post("/api/auth/social/signup").session(session),
            Map.of(
                "name", "김소셜", "nickname", nickname(), "agreeTerms", true, "agreePrivacy", false))
        .andExpect(status().isBadRequest());

    String nickname = nickname();
    MockHttpServletResponse signup =
        perform(
                post("/api/auth/social/signup").session(session),
                Map.of(
                    "name", "김소셜", "nickname", nickname, "agreeTerms", true, "agreePrivacy", true))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.nickname").value(nickname))
            .andReturn()
            .getResponse();

    User user = userRepository.findByEmail(email).orElseThrow();
    assertThat(user.hasPassword()).isFalse();
    assertThat(user.getPhone()).isNull();
    assertThat(user.getName()).isEqualTo("김소셜");
    assertThat(user.getTermsAgreedAt()).isNotNull();
    assertThat(socialAccountRepository.findWithUser(SocialProvider.GOOGLE, sub))
        .get()
        .extracting(s -> s.getUser().getId())
        .isEqualTo(user.getId());
    Cookie cookie = signup.getCookie(AuthCookies.ACCESS_TOKEN);
    mockMvc
        .perform(get("/api/me").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.hasPassword").value(false));

    // 세션의 가입 정보는 한 번만 쓴다
    perform(get("/api/auth/social/pending").session(session))
        .andExpect(jsonPath("$.code").value("SOCIAL_SIGNUP_EXPIRED"));
    // 비밀번호가 없으니 이메일·비밀번호 로그인은 틀린 비밀번호와 같다
    perform(post("/api/auth/login"), Map.of("email", email, "password", PASSWORD))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    // 전화번호 없이 내 정보를 저장할 수 있다
    perform(put("/api/me").cookie(cookie), Map.of("nickname", nickname, "phone", "", "bio", "안녕"))
        .andExpect(status().isOk());
    // 다음 소셜 로그인은 바로 된다
    assertThat(callback(new MockHttpSession(), google(email, true, "김소셜")).getRedirectedUrl())
        .isEqualTo("/");
  }

  @Test
  void duplicateNicknameIsRejectedAndCanRetry() throws Exception {
    User other = newUser("o" + email);
    MockHttpSession session = new MockHttpSession();
    callback(session, google(email, true, "김소셜"));

    perform(
            post("/api/auth/social/signup").session(session),
            Map.of(
                "name",
                "김소셜",
                "nickname",
                other.getNickname(),
                "agreeTerms",
                true,
                "agreePrivacy",
                true))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));
    assertThat(userRepository.findByEmail(email)).isEmpty();

    perform(
            post("/api/auth/social/signup").session(session),
            Map.of("name", "김소셜", "nickname", nickname(), "agreeTerms", true, "agreePrivacy", true))
        .andExpect(status().isCreated());
  }

  @Test
  void signupWithoutSocialLoginIsRejected() throws Exception {
    perform(
            post("/api/auth/social/signup"),
            Map.of("name", "김소셜", "nickname", nickname(), "agreeTerms", true, "agreePrivacy", true))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SOCIAL_SIGNUP_EXPIRED"));
  }

  @Test
  void withdrawnUserIsNotMatched() throws Exception {
    User user = newUser(email);
    socialAccountRepository.save(new SocialAccount(user, SocialProvider.GOOGLE, sub, email));
    // 연결을 지우지 않던 옛 버전에서 탈퇴한 경우: 이메일은 비고 연결은 남아 있다
    jdbc.update(
        "update users set status = 'WITHDRAWN', email = null, withdrawn_at = ? where id = ?",
        LocalDateTime.now(clock),
        user.getId());

    MockHttpSession session = new MockHttpSession();
    MockHttpServletResponse response = callback(session, google(email, true, "홍길동"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/social-signup.html");
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(socialAccountRepository.findByUserIdOrderByIdAsc(user.getId())).isEmpty();
    perform(
            post("/api/auth/social/signup").session(session),
            Map.of("name", "홍길동", "nickname", nickname(), "agreeTerms", true, "agreePrivacy", true))
        .andExpect(status().isCreated());
    assertThat(userRepository.findByEmail(email).orElseThrow().getId()).isNotEqualTo(user.getId());
  }

  @Test
  void withdrawRemovesLinksSoTheAccountCanSignUpAgain() throws Exception {
    MockHttpSession session = new MockHttpSession();
    callback(session, google(email, true, "김소셜"));
    Cookie cookie =
        perform(
                post("/api/auth/social/signup").session(session),
                Map.of(
                    "name",
                    "김소셜",
                    "nickname",
                    nickname(),
                    "agreeTerms",
                    true,
                    "agreePrivacy",
                    true))
            .andReturn()
            .getResponse()
            .getCookie(AuthCookies.ACCESS_TOKEN);
    Long userId = userRepository.findByEmail(email).orElseThrow().getId();

    // 비밀번호가 없으니 비밀번호 없이 탈퇴한다
    perform(post("/api/me/withdraw").cookie(cookie), Map.of()).andExpect(status().isNoContent());

    assertThat(socialAccountRepository.findByUserIdOrderByIdAsc(userId)).isEmpty();
    assertThat(callback(new MockHttpSession(), google(email, true, "김소셜")).getRedirectedUrl())
        .isEqualTo("/social-signup.html");
  }

  // ---------- 내 정보에서 연결·해제 ----------

  @Test
  void linkFromMePageNeedsTheSameLoggedInUser() throws Exception {
    User user = newUser(email);
    Cookie cookie = accessTokenOf(passwordLogin(user));

    MockHttpSession session =
        (MockHttpSession)
            perform(post("/api/me/social/google/link").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/oauth2/authorization/google?link=1"))
                .andReturn()
                .getRequest()
                .getSession();

    // 이메일이 달라도 본인이 로그인해 있으니 연결한다
    MockHttpServletResponse response =
        callback(session, google("other-" + email, false, "홍길동"), cookie);
    assertThat(response.getRedirectedUrl()).isEqualTo("/me.html?social_linked=google");
    perform(get("/api/me/social").cookie(cookie))
        .andExpect(jsonPath("$.hasPassword").value(true))
        .andExpect(jsonPath("$.accounts[0].linked").value(true));

    // 다른 회원이 같은 구글 계정을 연결하려 하면 막는다
    User other = newUser("x" + email);
    Cookie otherCookie = accessTokenOf(passwordLogin(other));
    MockHttpSession otherSession =
        (MockHttpSession)
            perform(post("/api/me/social/google/link").cookie(otherCookie))
                .andReturn()
                .getRequest()
                .getSession();
    assertThat(callback(otherSession, google(email, true, "홍길동"), otherCookie).getRedirectedUrl())
        .isEqualTo("/me.html?social_error=already_linked");

    // 연결을 시작한 회원의 로그인 쿠키가 없으면 연결하지 않는다
    MockHttpSession noCookie = new MockHttpSession();
    noCookie.setAttribute(SocialSession.LINK_USER_ID, other.getId());
    sub = "g-" + UUID.randomUUID();
    assertThat(callback(noCookie, google("y" + email, true, "홍길동")).getRedirectedUrl())
        .isEqualTo("/me.html?social_error=failed");
    assertThat(socialAccountRepository.findByUserIdOrderByIdAsc(other.getId())).isEmpty();
  }

  @Test
  void lastLoginMethodCannotBeUnlinkedUntilPasswordIsSet() throws Exception {
    MockHttpSession session = new MockHttpSession();
    callback(session, google(email, true, "김소셜"));
    Cookie cookie =
        perform(
                post("/api/auth/social/signup").session(session),
                Map.of(
                    "name",
                    "김소셜",
                    "nickname",
                    nickname(),
                    "agreeTerms",
                    true,
                    "agreePrivacy",
                    true))
            .andReturn()
            .getResponse()
            .getCookie(AuthCookies.ACCESS_TOKEN);

    perform(delete("/api/me/social/google").cookie(cookie))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SOCIAL_LAST_LOGIN_METHOD"));

    // 비밀번호가 없으면 현재 비밀번호 없이 만든다
    MockHttpServletResponse changed =
        perform(put("/api/me/password").cookie(cookie), Map.of("newPassword", PASSWORD))
            .andExpect(status().isNoContent())
            .andReturn()
            .getResponse();
    Cookie newCookie = changed.getCookie(AuthCookies.ACCESS_TOKEN);

    perform(delete("/api/me/social/google").cookie(newCookie)).andExpect(status().isNoContent());
    assertThat(socialAccountRepository.findWithUser(SocialProvider.GOOGLE, sub)).isEmpty();
    // 이제 비밀번호로 로그인한다
    perform(post("/api/auth/login"), Map.of("email", email, "password", PASSWORD))
        .andExpect(status().isOk());
  }

  // ---------- 잠금·제공자 계정 하나 ----------

  @Test
  @DisplayName("이미 연결된 소셜 계정이라도 로그인 잠금 중이면 들어가지 못한다 (D-128, 5번 실패 5분)")
  void linkedAccountIsBlockedWhileLocked() throws Exception {
    User user = newUser(email);
    assertThat(callback(new MockHttpSession(), google(email, true, "홍길동")).getRedirectedUrl())
        .isEqualTo("/");
    jdbc.update(
        "update users set login_fail_count = 5, locked_until = ? where id = ?",
        LocalDateTime.now(clock).plusMinutes(5),
        user.getId());

    // 연결된 계정은 이메일을 보지 않고 제공자 회원 번호로 찾는다
    MockHttpServletResponse response =
        callback(new MockHttpSession(), google("other-" + email, false, "홍길동"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/login.html?social_error=locked");
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  @Test
  @DisplayName("같은 이메일 회원에게 다른 구글 계정이 이미 연결돼 있으면 새 구글 계정을 붙이지 않는다 (회원당 제공자별 하나, D-128)")
  void secondGoogleAccountWithSameEmailIsRefused() throws Exception {
    User user = newUser(email);
    socialAccountRepository.save(
        new SocialAccount(user, SocialProvider.GOOGLE, "g-first-" + UUID.randomUUID(), email));

    MockHttpServletResponse response = callback(new MockHttpSession(), google(email, true, "홍길동"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/login.html?social_error=provider_taken");
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(socialAccountRepository.findWithUser(SocialProvider.GOOGLE, sub)).isEmpty();
  }

  @Test
  @DisplayName("내 정보에서 같은 구글 계정을 다시 연결하면 그대로 두고, 다른 구글 계정은 먼저 끊어야 연결된다 (D-128)")
  void relinkingFromMePageKeepsOneAccountPerProvider() throws Exception {
    User user = newUser(email);
    Cookie cookie = accessTokenOf(passwordLogin(user));
    assertThat(callback(startLink(cookie), google(email, true, "홍길동"), cookie).getRedirectedUrl())
        .isEqualTo("/me.html?social_linked=google");

    assertThat(callback(startLink(cookie), google(email, true, "홍길동"), cookie).getRedirectedUrl())
        .isEqualTo("/me.html?social_linked=google");
    assertThat(socialAccountRepository.findByUserIdOrderByIdAsc(user.getId())).hasSize(1);

    String firstSub = sub;
    sub = "g-" + UUID.randomUUID();
    assertThat(callback(startLink(cookie), google(email, true, "홍길동"), cookie).getRedirectedUrl())
        .isEqualTo("/me.html?social_error=provider_taken");
    assertThat(socialAccountRepository.findByUserIdOrderByIdAsc(user.getId()))
        .singleElement()
        .extracting(SocialAccount::getProviderUserId)
        .isEqualTo(firstSub);
  }

  // ---------- 가입 마무리의 경합 ----------

  @Test
  @DisplayName("가입 마무리 중 같은 이메일로 다른 가입이 끝났으면 가입하지 않는다. 약관은 둘 다 동의해야 한다 (D-128, USR-01)")
  void signupStopsWhenTheEmailWasTakenMeanwhile() throws Exception {
    MockHttpSession session = new MockHttpSession();
    callback(session, google(email, true, "김소셜"));

    perform(
            post("/api/auth/social/signup").session(session),
            Map.of(
                "name", "김소셜", "nickname", nickname(), "agreeTerms", false, "agreePrivacy", true))
        .andExpect(status().isBadRequest());

    User other = newUser(email);
    perform(
            post("/api/auth/social/signup").session(session),
            Map.of("name", "김소셜", "nickname", nickname(), "agreeTerms", true, "agreePrivacy", true))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    assertThat(socialAccountRepository.findWithUser(SocialProvider.GOOGLE, sub)).isEmpty();
    assertThat(socialAccountRepository.findByUserIdOrderByIdAsc(other.getId())).isEmpty();
  }

  @Test
  @DisplayName("가입 마무리 중 그 소셜 계정이 다른 회원에게 연결됐으면 가입하지 않는다 (D-128)")
  void signupStopsWhenTheSocialAccountWasLinkedMeanwhile() throws Exception {
    MockHttpSession session = new MockHttpSession();
    callback(session, google(email, true, "김소셜"));
    User other = newUser("o" + email);
    socialAccountRepository.save(new SocialAccount(other, SocialProvider.GOOGLE, sub, email));

    perform(
            post("/api/auth/social/signup").session(session),
            Map.of("name", "김소셜", "nickname", nickname(), "agreeTerms", true, "agreePrivacy", true))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("이미 가입된 소셜 계정")));
    assertThat(userRepository.findByEmail(email)).isEmpty();
  }

  @Test
  @DisplayName("10분이 지난 가입 정보로는 가입할 수 없다 (D-128)")
  void expiredPendingSignupIsRejected() throws Exception {
    PendingSocialSignup expired =
        new PendingSocialSignup(
            SocialProvider.GOOGLE, sub, email, "김소셜", false, clock.instant().minusSeconds(1));
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(SocialSession.PENDING, expired);

    perform(
            post("/api/auth/social/signup").session(session),
            Map.of("name", "김소셜", "nickname", nickname(), "agreeTerms", true, "agreePrivacy", true))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SOCIAL_SIGNUP_EXPIRED"));
    // 화면을 거치지 않고 불러도 서비스가 막는다
    SocialLoginService.SignupForm form =
        new SocialLoginService.SignupForm("김소셜", nickname(), true, true);
    assertThatThrownBy(() -> socialLoginService.completeSignup(expired, form))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.SOCIAL_SIGNUP_EXPIRED);
    assertThat(userRepository.findByEmail(email)).isEmpty();
  }

  // ---------- 해제 ----------

  @Test
  @DisplayName("비밀번호가 없어도 다른 소셜 연결이 남아 있으면 끊을 수 있고, 연결 안 된 제공자는 끊을 것이 없다 (D-128)")
  void passwordlessUserCanUnlinkWhileAnotherLinkRemains() throws Exception {
    MockHttpSession session = new MockHttpSession();
    callback(session, google(email, true, "김소셜"));
    Cookie cookie =
        perform(
                post("/api/auth/social/signup").session(session),
                Map.of(
                    "name",
                    "김소셜",
                    "nickname",
                    nickname(),
                    "agreeTerms",
                    true,
                    "agreePrivacy",
                    true))
            .andReturn()
            .getResponse()
            .getCookie(AuthCookies.ACCESS_TOKEN);
    User user = userRepository.findByEmail(email).orElseThrow();

    perform(delete("/api/me/social/github").cookie(cookie))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("연결된 계정이 없어요."));

    socialAccountRepository.save(
        new SocialAccount(user, SocialProvider.GITHUB, "gh-" + UUID.randomUUID(), email));
    perform(delete("/api/me/social/google").cookie(cookie)).andExpect(status().isNoContent());
    // 이제 남은 깃허브가 마지막 방법이라 끊을 수 없다
    perform(delete("/api/me/social/github").cookie(cookie))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SOCIAL_LAST_LOGIN_METHOD"));
    assertThat(socialAccountRepository.findByUserIdOrderByIdAsc(user.getId()))
        .extracting(SocialAccount::getProvider)
        .containsExactly(SocialProvider.GITHUB);
  }

  // ---------- 도우미 ----------

  /** 내 정보 화면의 "연결" 버튼: 세션에 연결할 회원을 적는다 */
  private MockHttpSession startLink(Cookie cookie) throws Exception {
    return (MockHttpSession)
        perform(post("/api/me/social/google/link").cookie(cookie))
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession();
  }

  /** 구글 OIDC가 돌려줬을 회원 정보 */
  private OAuth2User google(String email, boolean verified, String name) {
    Map<String, Object> attrs = new HashMap<>();
    attrs.put("sub", sub);
    attrs.put("email", email);
    attrs.put("email_verified", verified);
    attrs.put("name", name);
    return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attrs, "sub");
  }

  private MockHttpServletResponse callback(
      MockHttpSession session, OAuth2User user, Cookie... cookies) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
    request.setRemoteAddr(ip);
    request.setSession(session);
    if (cookies.length > 0) {
      request.setCookies(cookies);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    handlers.onAuthenticationSuccess(
        request,
        response,
        new OAuth2AuthenticationToken(user, user.getAuthorities(), SocialProvider.GOOGLE.getId()));
    return response;
  }

  private ResultActions perform(MockHttpServletRequestBuilder builder) throws Exception {
    return perform(builder, null);
  }

  private ResultActions perform(MockHttpServletRequestBuilder builder, Map<String, ?> body)
      throws Exception {
    builder
        .with(csrf())
        .with(
            request -> {
              request.setRemoteAddr(ip);
              return request;
            });
    if (body != null) {
      builder.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
    }
    return mockMvc.perform(builder);
  }

  private MockHttpServletResponse passwordLogin(User user) throws Exception {
    return perform(post("/api/auth/login"), Map.of("email", user.getEmail(), "password", PASSWORD))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse();
  }

  private static Cookie accessTokenOf(MockHttpServletResponse response) {
    return response.getCookie(AuthCookies.ACCESS_TOKEN);
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
}
