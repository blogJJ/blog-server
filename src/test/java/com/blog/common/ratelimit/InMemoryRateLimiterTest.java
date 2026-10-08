package com.blog.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** IP별 요청 제한 (T022, SCL-01). */
class InMemoryRateLimiterTest {

  MutableClock clock = new MutableClock();
  InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);

  @Test
  void allowsUpToLimitThenBlocksWithRetryAfter() {
    Duration minute = Duration.ofMinutes(1);
    assertThat(limiter.tryAcquire("api:1.1.1.1", 2, minute).allowed()).isTrue();
    assertThat(limiter.tryAcquire("api:1.1.1.1", 2, minute).allowed()).isTrue();

    clock.advance(Duration.ofSeconds(20));
    RateLimiter.Decision blocked = limiter.tryAcquire("api:1.1.1.1", 2, minute);

    assertThat(blocked.allowed()).isFalse();
    assertThat(blocked.retryAfterSeconds()).isEqualTo(40);
  }

  @Test
  void keysAreCountedSeparately() {
    Duration minute = Duration.ofMinutes(1);
    limiter.tryAcquire("api:1.1.1.1", 1, minute);

    assertThat(limiter.tryAcquire("api:2.2.2.2", 1, minute).allowed()).isTrue();
    assertThat(limiter.tryAcquire("login:1.1.1.1", 1, minute).allowed()).isTrue();
  }

  @Test
  void countResetsWhenWindowEnds() {
    Duration minute = Duration.ofMinutes(1);
    limiter.tryAcquire("k", 1, minute);
    assertThat(limiter.tryAcquire("k", 1, minute).allowed()).isFalse();

    clock.advance(minute);

    assertThat(limiter.tryAcquire("k", 1, minute).allowed()).isTrue();
  }

  @Test
  void finishedWindowsAreCleanedUp() {
    for (int i = 0; i < 500; i++) {
      limiter.tryAcquire("ip" + i, 10, Duration.ofSeconds(1));
    }
    clock.advance(Duration.ofSeconds(2));
    for (int i = 0; i < 500; i++) {
      limiter.tryAcquire("same", 10_000, Duration.ofSeconds(1));
    }

    assertThat(limiter.size()).isEqualTo(1);
  }

  static class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-08T00:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("Asia/Seoul");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
