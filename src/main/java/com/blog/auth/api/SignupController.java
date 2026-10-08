package com.blog.auth.api;

import com.blog.auth.domain.User;
import com.blog.auth.service.LoginSessionService;
import com.blog.auth.service.SignupService;
import com.blog.auth.service.SignupService.SignupForm;
import com.blog.common.ratelimit.RateLimitGuard;
import com.blog.common.security.AuthCookies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 가입 API (T031, T032, USR-01, USR-02). 비회원이 부른다. */
@RestController
@RequestMapping("/api/auth/signup")
public class SignupController {

  private final SignupService signupService;
  private final LoginSessionService sessionService;
  private final AuthCookies authCookies;
  private final RateLimitGuard rateLimitGuard;

  public SignupController(
      SignupService signupService,
      LoginSessionService sessionService,
      AuthCookies authCookies,
      RateLimitGuard rateLimitGuard) {
    this.signupService = signupService;
    this.sessionService = sessionService;
    this.authCookies = authCookies;
    this.rateLimitGuard = rateLimitGuard;
  }

  public record CodeRequest(String email) {}

  public record VerifyRequest(String email, String code) {}

  public record SignupResponse(Long id, String nickname) {}

  /** 인증번호 보내기. 가입된 이메일이어도 같은 응답 (D-28) */
  @PostMapping("/code")
  public Map<String, String> sendCode(@RequestBody CodeRequest body, HttpServletRequest request) {
    rateLimitGuard.check("signup-code", request, 10, Duration.ofMinutes(10));
    signupService.sendSignupCode(body.email());
    return Map.of("message", "인증번호를 보냈어요. 메일함을 확인해 주세요.");
  }

  @PostMapping("/verify")
  public Map<String, String> verify(@RequestBody VerifyRequest body, HttpServletRequest request) {
    rateLimitGuard.check("signup-verify", request, 30, Duration.ofMinutes(10));
    signupService.verifySignupCode(body.email(), body.code());
    return Map.of("message", "이메일 인증이 끝났어요.");
  }

  /** 가입을 끝내고 바로 로그인한다("로그인 유지" 안 함). */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SignupResponse signup(
      @RequestBody SignupForm form, HttpServletRequest request, HttpServletResponse response) {
    rateLimitGuard.check("signup", request, 10, Duration.ofMinutes(10));
    User user = signupService.signup(form);
    LoginSessionService.Issued issued =
        sessionService.start(user, false, request.getHeader(HttpHeaders.USER_AGENT));
    authCookies.write(response, issued.accessToken(), issued.refreshToken(), issued.cookieMaxAge());
    return new SignupResponse(user.getId(), user.getNickname());
  }
}
