package com.blog.main.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Component;

/**
 * 목록의 전체 개수를 잠깐 기억한다 (T175, D-151). 글 30만 건에서 피드·검색의 COUNT 쿼리가 페이지마다 20만 행 넘게 다시 읽어 응답 시간의 절반을
 * 차지했다.
 *
 * <p>기억하는 값은 지워져도 다시 세면 되는 임시 값이라 "서버 메모리에 상태를 두지 않는다"(원칙 IV)의 예외로 둔다. 앱이 두 대면 각자 센다. 쪽수가 최대 {@code
 * ttl}만큼 늦게 바뀔 수 있다. 0이면 기억하지 않는다(테스트).
 */
@Component
public class CountCache {

  /** 이 개수를 넘으면 지난 값부터 비운다. 검색어마다 하나씩 생기므로 끝없이 늘지 않게 한다 */
  static final int MAX_ENTRIES = 2_000;

  private record Entry(long count, Instant expiresAt) {}

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final Clock clock;

  public CountCache(@Value("${app.count-cache.ttl:60s}") Duration ttl, Clock clock) {
    this.ttl = ttl;
    this.clock = clock;
  }

  /**
   * 한 페이지 목록에 전체 개수를 붙인다. 첫 페이지가 다 차지 않았거나 마지막 페이지라 개수를 알 수 있으면 세지 않는다.
   *
   * @param key 같은 개수를 나누어 쓰는 조건 (예: 탭·검색어·차단 목록). 정렬은 넣지 않는다
   */
  public <T> Page<T> page(List<T> content, Pageable pageable, String key, LongSupplier count) {
    return PageableExecutionUtils.getPage(content, pageable, () -> count(key, count));
  }

  long count(String key, LongSupplier count) {
    if (ttl.isZero() || ttl.isNegative()) {
      return count.getAsLong();
    }
    Instant now = clock.instant();
    Entry cached = entries.get(key);
    if (cached != null && now.isBefore(cached.expiresAt())) {
      return cached.count();
    }
    long value = count.getAsLong();
    if (entries.size() >= MAX_ENTRIES) {
      entries.values().removeIf(e -> !now.isBefore(e.expiresAt()));
      if (entries.size() >= MAX_ENTRIES) {
        entries.clear();
      }
    }
    entries.put(key, new Entry(value, now.plus(ttl)));
    return value;
  }
}
