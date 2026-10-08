package com.blog.common.logging;

import com.blog.common.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 보안 이벤트를 {@code security} 로거로 남긴다 (T137, OPS-03, D-103).
 *
 * <p>지금은 권한 거부(403)만 남긴다. 로그인 실패(가린 이메일, IP)는 로그인(T034)과 Masking(T019)을, 요청 제한 초과(429)는
 * RateLimiter(T022)를 만들 때 여기에 더한다. 비밀번호·토큰·인증번호·쿠키·CSRF 토큰은 넘기지 않는다.
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

  private static String currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
      return String.valueOf(user.id());
    }
    return "-";
  }

  /** 줄바꿈으로 로그 줄을 위조하지 못하게 한다. */
  static String sanitize(String value) {
    return value == null ? "" : value.replaceAll("[\\r\\n\\t]", "_");
  }
}
