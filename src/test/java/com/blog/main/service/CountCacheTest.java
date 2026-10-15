package com.blog.main.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/** 목록 개수 기억 (T175, D-151) */
class CountCacheTest {

  private Instant now = Instant.parse("2026-10-15T00:00:00Z");
  private final Clock clock =
      new Clock() {
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
      };

  private final AtomicInteger counted = new AtomicInteger();

  private long countOf(long value) {
    counted.incrementAndGet();
    return value;
  }

  @Test
  void sameKeyIsCountedOnceUntilTtlPasses() {
    CountCache cache = new CountCache(Duration.ofSeconds(60), clock);
    PageRequest second = PageRequest.of(1, 10);
    List<Long> full = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

    assertThat(cache.page(full, second, "public:[]", () -> countOf(300)).getTotalElements())
        .isEqualTo(300);
    // 1분 안에는 글이 늘어도 기억한 개수를 쓴다
    now = now.plusSeconds(59);
    Page<Long> again = cache.page(full, second, "public:[]", () -> countOf(301));
    assertThat(again.getTotalElements()).isEqualTo(300);
    assertThat(again.getTotalPages()).isEqualTo(30);
    assertThat(counted).hasValue(1);

    // 다른 조건(차단 목록·검색어)은 따로 센다
    assertThat(cache.page(full, second, "public:[7]", () -> countOf(250)).getTotalElements())
        .isEqualTo(250);

    now = now.plusSeconds(1);
    assertThat(cache.page(full, second, "public:[]", () -> countOf(301)).getTotalElements())
        .isEqualTo(301);
    assertThat(counted).hasValue(3);
  }

  @Test
  void countIsSkippedWhenThePageItselfShowsTheTotal() {
    CountCache cache = new CountCache(Duration.ofSeconds(60), clock);

    // 첫 페이지가 다 차지 않으면 그 개수가 전체다
    Page<Long> few =
        cache.page(List.of(1L, 2L, 3L), PageRequest.of(0, 10), "tag:x", () -> countOf(99));
    assertThat(few.getTotalElements()).isEqualTo(3);
    // 마지막 페이지도 앞 페이지 수로 안다
    Page<Long> last =
        cache.page(List.of(1L, 2L), PageRequest.of(2, 10), "tag:y", () -> countOf(99));
    assertThat(last.getTotalElements()).isEqualTo(22);
    assertThat(counted).hasValue(0);
  }

  @Test
  void zeroTtlAlwaysCounts() {
    CountCache cache = new CountCache(Duration.ZERO, clock);

    cache.count("k", () -> countOf(1));
    cache.count("k", () -> countOf(2));

    assertThat(cache.count("k", () -> countOf(3))).isEqualTo(3);
    assertThat(counted).hasValue(3);
  }

  @Test
  void entriesDoNotGrowWithoutBound() {
    CountCache cache = new CountCache(Duration.ofSeconds(60), clock);
    for (int i = 0; i < CountCache.MAX_ENTRIES; i++) {
      cache.count("q" + i, () -> 1L);
    }
    // 가득 찼는데 아직 유효한 값뿐이면 모두 비우고 새로 센다
    cache.count("new", () -> countOf(5));
    assertThat(cache.count("q0", () -> countOf(7))).isEqualTo(7);
    assertThat(cache.count("new", () -> countOf(9))).isEqualTo(5);
    assertThat(counted).hasValue(2);
  }
}
