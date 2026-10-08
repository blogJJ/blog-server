package com.blog.auth.api;

import com.blog.auth.domain.RefreshToken;
import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.service.ActivityPolicy;
import com.blog.auth.service.LoginService;
import com.blog.auth.service.LoginSessionService;
import com.blog.auth.service.TurnstileVerifier;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.ratelimit.RateLimitGuard;
import com.blog.common.security.AuthCookies;
import com.blog.common.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인·토큰 재발급·로그아웃·로그인 연장 (T034~T037, USR-03, USR-04, SEC-04). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final LoginService loginService;
  private final LoginSessionService sessionService;
  private final ActivityPolicy activityPolicy;
  private final TurnstileVerifier turnstileVerifier;
  private final AuthCookies authCookies;
  private final RateLimitGuard rateLimitGuard;
  private final Clock clock;

  public AuthController(
      LoginService loginService,
      LoginSessionService sessionService,
      ActivityPolicy activityPolicy,
      TurnstileVerifier turnstileVerifier,
      AuthCookies authCookies,
      RateLimitGuard rateLimitGuard,
      Clock clock) {
    this.loginService = loginService;
    this.sessionService = sessionService;
    this.activityPolicy = activityPolicy;
    this.turnstileVerifier = turnstileVerifier;
    this.authCookies = authCookies;
    this.rateLimitGuard = rateLimitGuard;
    this.clock = clock;
  }

  public record LoginRequest(
      String email, String password, Boolean rememberMe, String turnstileToken) {}

  public record LoginResponse(Long id, String nickname, UserRole role) {}

  /** 로그인 화면에 필요한 공개 설정 */
  public record AuthConfig(String turnstileSiteKey) {}

  /**
   * @param expiresAt "로그인 유지"를 안 한 로그인이 끝나는 시각(서울 시각)
   * @param expiresInSeconds 끝날 때까지 남은 초. 브라우저 시계·시간대와 상관없이 session.js가 만료 5분 전 안내에 쓴다 (D-62)
   */
  public record SessionInfo(boolean rememberMe, LocalDateTime expiresAt, Long expiresInSeconds) {}

  @GetMapping("/config")
  public AuthConfig config() {
    return new AuthConfig(turnstileVerifier.getSiteKey());
  }

  @PostMapping("/login")
  public LoginResponse login(
      @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
    rateLimitGuard.check("login", request, 10, Duration.ofMinutes(1));
    User user =
        loginService.authenticate(
            body.email(), body.password(), body.turnstileToken(), request.getRemoteAddr());
    LoginSessionService.Issued issued =
        sessionService.start(
            user,
            Boolean.TRUE.equals(body.rememberMe()),
            request.getHeader(HttpHeaders.USER_AGENT));
    authCookies.write(response, issued.accessToken(), issued.refreshToken(), issued.cookieMaxAge());
    return new LoginResponse(user.getId(), user.getNickname(), user.getRole());
  }

  /** Access Token이 만료되면 api.js가 부른다. 로그인이 끝났으면 쿠키를 지우고 401. */
  @PostMapping("/refresh")
  public ResponseEntity<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
    rateLimitGuard.check("refresh", request, 60, Duration.ofMinutes(1));
    return sessionService
        .refresh(AuthCookies.read(request, AuthCookies.REFRESH_TOKEN))
        .map(
            issued -> {
              authCookies.write(response, issued.accessToken(), null, issued.cookieMaxAge());
              return ResponseEntity.noContent().<Void>build();
            })
        .orElseGet(
            () -> {
              authCookies.clear(response);
              throw new BusinessException(ErrorCode.UNAUTHORIZED);
            });
  }

  /** 로그아웃: 서버에서 로그인을 폐기하고 쿠키를 지운다. 이미 로그아웃이어도 성공 (USR-04) */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @AuthenticationPrincipal AuthUser user,
      HttpServletRequest request,
      HttpServletResponse response) {
    String refreshToken = AuthCookies.read(request, AuthCookies.REFRESH_TOKEN);
    if (refreshToken != null) {
      sessionService.revoke(refreshToken);
    }
    if (user != null && user.sessionId() != null) {
      sessionService.revoke(user.sessionId());
    }
    authCookies.clear(response);
    return ResponseEntity.noContent().build();
  }

  /** 지금 로그인이 언제 끝나는지. session.js가 X-Auto-Request로 불러 연장으로 세지 않는다. */
  @GetMapping("/session")
  public SessionInfo session(@AuthenticationPrincipal AuthUser user) {
    return sessionInfo(user);
  }

  /** 로그인 연장: 만료 안내에서 "연장"을 누르거나 글쓰기 중 입력이 있을 때 (D-62) */
  @PostMapping("/extend")
  public SessionInfo extend(@AuthenticationPrincipal AuthUser user) {
    RefreshToken session = requireSession(user);
    activityPolicy.extend(session);
    return sessionInfo(user);
  }

  private SessionInfo sessionInfo(AuthUser user) {
    RefreshToken session = requireSession(user);
    if (session.isRememberMe()) {
      return new SessionInfo(true, null, null);
    }
    long seconds =
        Math.max(0, Duration.between(LocalDateTime.now(clock), session.getExpiresAt()).toSeconds());
    return new SessionInfo(false, session.getExpiresAt(), seconds);
  }

  private RefreshToken requireSession(AuthUser user) {
    if (user == null || user.sessionId() == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
    return sessionService
        .findActive(user.sessionId())
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
  }
}
