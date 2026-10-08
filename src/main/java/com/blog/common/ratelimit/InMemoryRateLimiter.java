package com.blog.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 서버 메모리에서 세는 요청 제한 (T022, SCL-01). 기간을 고정 구간으로 나눠 센다(예: 1분 제한이면 구간이 바뀔 때 0으로).
 *
 * <p>서버 메모리에 두는 유일한 상태라 서버가 다시 뜨면 0부터 센다. 끝난 구간은 일정 횟수마다 한꺼번에 지워 메모리가 계속 늘지 않게 한다.
 */
@Component
public class InMemoryRateLimiter implements RateLimiter {

  /** 이만큼 부를 때마다 끝난 구간을 지운다. */
  private static final long CLEANUP_EVERY = 1_000;

  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private final AtomicLong calls = new AtomicLong();
  private final Clock clock;

  public InMemoryRateLimiter(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Decision tryAcquire(String key, int limit, Duration window) {
    long now = clock.millis();
    long windowMillis = window.toMillis();
    if (calls.incrementAndGet() % CLEANUP_EVERY == 0) {
      windows.values().removeIf(w -> w.endsAt <= now);
    }
    Window current =
        windows.compute(
            key,
            (k, w) -> {
              if (w == null || w.endsAt <= now) {
                return new Window(now + windowMillis, 1);
              }
              return new Window(w.endsAt, w.count + 1);
            });
    if (current.count <= limit) {
      return new Decision(true, 0);
    }
    long retryAfter = Math.max(1, (current.endsAt - now + 999) / 1000);
    return new Decision(false, retryAfter);
  }

  int size() {
    return windows.size();
  }

  private record Window(long endsAt, int count) {}
}
