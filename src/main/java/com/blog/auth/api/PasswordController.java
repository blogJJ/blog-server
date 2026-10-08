package com.blog.auth.api;

import com.blog.auth.service.PasswordResetService;
import com.blog.common.ratelimit.RateLimitGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 비밀번호 찾기·재설정 (T096, USR-06). 비회원이 부른다. 이메일 대신 이메일 찾기 결과의 임시 토큰(findToken)을 보내도 된다. */
@RestController
@RequestMapping("/api/auth/password")
public class PasswordController {

  private final PasswordResetService resetService;
  private final RateLimitGuard rateLimitGuard;

  public PasswordController(PasswordResetService resetService, RateLimitGuard rateLimitGuard) {
    this.resetService = resetService;
    this.rateLimitGuard = rateLimitGuard;
  }

  public record CodeRequest(String email, String findToken) {}

  /** 새 비밀번호 확인(두 번 입력)은 화면에서 맞춘다 */
  public record ResetRequest(String email, String findToken, String code, String newPassword) {}

  /** 가입 여부와 관계없이 같은 문구 (SC-003) */
  @PostMapping("/code")
  public Map<String, String> sendCode(@RequestBody CodeRequest body, HttpServletRequest request) {
    rateLimitGuard.check("password-code", request, 10, Duration.ofMinutes(10));
    resetService.sendCode(body.email(), body.findToken());
    return Map.of("message", "가입된 이메일이면 인증번호를 보냈어요. 메일함을 확인해 주세요.");
  }

  @PostMapping("/reset")
  public Map<String, String> reset(@RequestBody ResetRequest body, HttpServletRequest request) {
    rateLimitGuard.check("password-reset", request, 30, Duration.ofMinutes(10));
    resetService.reset(body.email(), body.findToken(), body.code(), body.newPassword());
    return Map.of("message", "비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요.");
  }
}
