package com.blog.common.logging;

import com.blog.common.privacy.Masking;
import com.blog.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 보안 이벤트를 {@code security} 로거로 남긴다 (T137, OPS-03, D-103).
 *
 * <p>권한 거부(403), 요청 제한 초과(429), 로그인 실패(가린 이메일, IP)를 남긴다. 비밀번호·토큰·인증번호·쿠키·CSRF 토큰은 넘기지 않는다.
 */
public final class SecurityEventLogger {

  private static final Logger log = LoggerFactory.getLogger("security");

  private SecurityEventLogger() {}

  /** 권한 거부: 회원 번호(비회원은 -), 메서드, 주소. 쿼리 문자열은 남기지 않는다. */
  public static void accessDenied(HttpServletRequest request) {
    log.warn(
        "access denied user={} {} {}",
        currentUserId(),
        request.getMethod(),
        sanitize(request.getRequestURI()));
  }

  /** 요청 제한 초과: IP, 제한 이름, 메서드, 주소 (T022). */
  public static void rateLimited(String ip, String limitName, HttpServletRequest request) {
    log.warn(
        "rate limited ip={} limit={} {} {}",
        sanitize(ip),
        sanitize(limitName),
        request.getMethod(),
        sanitize(request.getRequestURI()));
  }

  /** 로그인 실패: 가린 이메일(4.4), IP. 비밀번호는 넘기지 않는다 (T034). */
  public static void loginFailed(String email, String ip) {
    log.warn("login failed email={} ip={}", sanitize(Masking.email(email)), sanitize(ip));
  }

  private static String currentUserId() {
    return CurrentUser.id().map(String::valueOf).orElse("-");
  }

  /** 줄바꿈으로 로그 줄을 위조하지 못하게 한다. */
  public static String sanitize(String value) {
    return value == null ? "" : value.replaceAll("[\\r\\n\\t]", "_");
  }
}
