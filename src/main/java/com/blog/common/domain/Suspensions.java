package com.blog.common.domain;

import java.time.LocalDateTime;

/** 정지 기간 계산. 영구 정지는 끝나는 시각을 9999-12-31로 저장한다 (BLG-13, ADM-08). */
public final class Suspensions {

  public static final LocalDateTime PERMANENT = LocalDateTime.of(9999, 12, 31, 0, 0);

  private Suspensions() {}

  /** suspendedUntil이 now보다 뒤면 정지 중이다. NULL이면 정지가 아니다. */
  public static boolean isSuspended(LocalDateTime suspendedUntil, LocalDateTime now) {
    return suspendedUntil != null && suspendedUntil.isAfter(now);
  }
}
