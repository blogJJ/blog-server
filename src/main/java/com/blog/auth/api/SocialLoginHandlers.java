package com.blog.auth.api;

import com.blog.auth.domain.SocialProvider;
import com.blog.auth.domain.User;
import com.blog.auth.service.LoginSessionService;
import com.blog.auth.service.SocialLoginService;
import com.blog.auth.service.SocialLoginService.LoggedIn;
import com.blog.auth.service.SocialLoginService.NeedsSignup;
import com.blog.auth.service.SocialLoginService.Outcome;
import com.blog.auth.service.SocialLoginService.Refused;
import com.blog.auth.service.SocialProfile;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.logging.SecurityEventLogger;
import com.blog.common.ratelimit.RateLimitGuard;
import com.blog.common.security.AuthCookies;
import com.blog.common.security.JwtProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 구글·깃허브에서 돌아온 뒤 ({@code /login/oauth2/code/{google|github}}) 할 일 (T157, D-128).
 *
 * <ul>
 *   <li>로그인: 일반 로그인과 똑같이 LoginSessionService로 로그인(refresh_tokens 한 행)을 만들고 같은 로그인 쿠키를 쓴다. Spring
 *       Security의 OAuth2 로그인 상태는 세션에 남기지 않는다.
 *   <li>가입 전이면 가입 마무리 화면(/social-signup.html)으로 보낸다.
 *   <li>내 정보 화면에서 시작한 연결이면, 지금 로그인 쿠키의 회원이 연결을 시작한 회원과 같을 때만 연결한다.
 *   <li>안 되면 로그인 화면(연결은 내 정보 화면)에 {@code ?social_error=까닭}을 붙여 보낸다. 화면이 문구를 고른다.
 *   <li>IP별 횟수 제한은 일반 로그인과 같은 1분 10번.
 * </ul>
 */
@Component
public class SocialLoginHandlers
    implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

  static final String LOGIN_PAGE = "/login.html";
  static final String ME_PAGE = "/me.html";
  static final String SIGNUP_PAGE = "/social-signup.html";

  /** 화면에 넘기는 social_error 값: 까닭을 따로 밝히지 않는 실패 */
  private static final String FAILED = "failed";

  private final SocialLoginService socialLoginService;
  private final LoginSessionService sessionService;
  private final JwtProvider jwtProvider;
  private final AuthCookies authCookies;
  private final RateLimitGuard rateLimitGuard;

  public SocialLoginHandlers(
      SocialLoginService socialLoginService,
      LoginSessionService sessionService,
      JwtProvider jwtProvider,
      AuthCookies authCookies,
      RateLimitGuard rateLimitGuard) {
    this.socialLoginService = socialLoginService;
    this.sessionService = sessionService;
    this.jwtProvider = jwtProvider;
    this.authCookies = authCookies;
    this.rateLimitGuard = rateLimitGuard;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    // 로그인 상태는 JWT 쿠키로만 만든다. 제공자 정보가 담긴 인증은 이 요청에서도 남기지 않는다
    SecurityContextHolder.clearContext();
    String next = SocialSession.takeNext(request);
    boolean rememberMe = SocialSession.takeRememberMe(request);
    Long linkUserId = SocialSession.takeLinkUserId(request);
    String failPage = linkUserId != null ? ME_PAGE : LOGIN_PAGE;

    Optional<SocialProvider> provider =
        authentication instanceof OAuth2AuthenticationToken token
            ? SocialProvider.fromId(token.getAuthorizedClientRegistrationId())
            : Optional.empty();
    if (provider.isEmpty()) {
      fail(request, response, failPage, "-", FAILED);
      return;
    }
    try {
      rateLimitGuard.check("social-login", request, 10, Duration.ofMinutes(1));
      SocialProfile profile =
          SocialProfile.from(
              provider.get(), ((OAuth2AuthenticationToken) authentication).getPrincipal());
      if (linkUserId != null) {
        link(request, response, linkUserId, profile);
      } else {
        login(request, response, profile, next, rememberMe);
      }
    } catch (BusinessException e) {
      String reason = e.getErrorCode() == ErrorCode.TOO_MANY_REQUESTS ? "rate_limited" : FAILED;
      fail(request, response, failPage, provider.get().getId(), reason);
    } catch (IllegalArgumentException e) {
      fail(request, response, failPage, provider.get().getId(), FAILED);
    }
  }

  /** 사용자가 제공자 화면에서 취소했거나, state가 맞지 않거나, 제공자와 통신이 안 됐을 때 */
  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    Long linkUserId = SocialSession.takeLinkUserId(request);
    SocialSession.takeNext(request);
    SocialSession.takeRememberMe(request);
    String reason =
        exception instanceof OAuth2AuthenticationException e ? e.getError().getErrorCode() : "-";
    SecurityEventLogger.socialLoginFailed("-", reason, request.getRemoteAddr());
    String code = "access_denied".equals(reason) ? "cancelled" : FAILED;
    response.sendRedirect((linkUserId != null ? ME_PAGE : LOGIN_PAGE) + "?social_error=" + code);
  }

  private void login(
      HttpServletRequest request,
      HttpServletResponse response,
      SocialProfile profile,
      String next,
      boolean rememberMe)
      throws IOException {
    Outcome outcome = socialLoginService.login(profile, rememberMe);
    switch (outcome) {
      case LoggedIn(User user) -> {
        startSession(request, response, user, rememberMe);
        response.sendRedirect(next);
      }
      case NeedsSignup(var pending) -> {
        SocialSession.savePending(request, pending);
        response.sendRedirect(SIGNUP_PAGE);
      }
      case Refused(var refusal) ->
          fail(request, response, LOGIN_PAGE, profile.provider().getId(), refusal.getCode());
    }
  }

  private void link(
      HttpServletRequest request,
      HttpServletResponse response,
      Long linkUserId,
      SocialProfile profile)
      throws IOException {
    // 연결을 시작한 회원이 지금도 이 브라우저에 로그인해 있어야 한다
    if (!linkUserId.equals(currentUserId(request))) {
      fail(request, response, ME_PAGE, profile.provider().getId(), FAILED);
      return;
    }
    Outcome outcome = socialLoginService.link(linkUserId, profile);
    if (outcome instanceof Refused(var refusal)) {
      fail(request, response, ME_PAGE, profile.provider().getId(), refusal.getCode());
      return;
    }
    response.sendRedirect(ME_PAGE + "?social_linked=" + profile.provider().getId());
  }

  /** 일반 로그인(AuthController.login)과 같은 로그인 쿠키 */
  void startSession(
      HttpServletRequest request, HttpServletResponse response, User user, boolean rememberMe) {
    LoginSessionService.Issued issued =
        sessionService.start(user, rememberMe, request.getHeader(HttpHeaders.USER_AGENT));
    authCookies.write(response, issued.accessToken(), issued.refreshToken(), issued.cookieMaxAge());
  }

  /** 이 요청의 로그인 쿠키가 가리키는 살아 있는 회원 번호. JwtCookieAuthFilter와 같은 확인 */
  private Long currentUserId(HttpServletRequest request) {
    return jwtProvider
        .parse(AuthCookies.read(request, AuthCookies.ACCESS_TOKEN))
        .flatMap(
            claims ->
                sessionService
                    .findActive(claims.sessionId())
                    .filter(s -> s.getUser().getId() == claims.userId()))
        .filter(s -> s.getUser().isActive())
        .map(s -> s.getUser().getId())
        .orElse(null);
  }

  private static void fail(
      HttpServletRequest request,
      HttpServletResponse response,
      String page,
      String provider,
      String reason)
      throws IOException {
    SecurityEventLogger.socialLoginFailed(provider, reason, request.getRemoteAddr());
    response.sendRedirect(page + "?social_error=" + reason);
  }
}
