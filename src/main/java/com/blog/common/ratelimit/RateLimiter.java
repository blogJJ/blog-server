package com.blog.common.ratelimit;

import java.time.Duration;

/**
 * 키(보통 {@code 이름:IP})별 요청 횟수 제한 (T022, SCL-01, D-80).
 *
 * <p>1차는 서버 메모리({@link InMemoryRateLimiter})에서 센다. 서버를 2대로 늘리면 Redis 구현으로 바꿔 서버끼리 함께 세게 한다(SC-008,
 * research.md 이중화 5). 쓰는 쪽은 이 인터페이스만 본다.
 *
 * <pre>{@code
 * if (!rateLimiter.tryAcquire("login:" + ip, 10, Duration.ofMinutes(1)).allowed()) {
 *   throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
 * }
 * }</pre>
 */
public interface RateLimiter {

  /**
   * 한 번 세고 허용 여부를 돌려준다.
   *
   * @param key 무엇을 누가 했는지. 예: {@code api:203.0.113.5}
   * @param limit window 안에 허용할 횟수
   * @param window 세는 기간
   */
  Decision tryAcquire(String key, int limit, Duration window);

  /**
   * @param allowed 허용이면 true
   * @param retryAfterSeconds 막혔을 때 몇 초 뒤 다시 되는지(응답 헤더 Retry-After). 허용이면 0
   */
  record Decision(boolean allowed, long retryAfterSeconds) {}
}
