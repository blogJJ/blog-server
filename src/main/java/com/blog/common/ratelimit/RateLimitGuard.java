package com.blog.common.ratelimit;

import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.logging.SecurityEventLogger;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** 기능별 IP 횟수 제한을 한 줄로 건다 (T022). 넘으면 TOO_MANY_REQUESTS(429)를 던지고 security 로그에 남긴다. */
@Component
public class RateLimitGuard {

  private final RateLimiter rateLimiter;

  public RateLimitGuard(RateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  /**
   * @param name 제한 이름. 키는 {@code name:IP}
   */
  public void check(String name, HttpServletRequest request, int limit, Duration window) {
    String ip = request.getRemoteAddr();
    if (!rateLimiter.tryAcquire(name + ":" + ip, limit, window).allowed()) {
      SecurityEventLogger.rateLimited(ip, name, request);
      throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
    }
  }
}
