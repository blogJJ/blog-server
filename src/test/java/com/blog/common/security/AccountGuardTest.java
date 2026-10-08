package com.blog.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
import com.blog.common.domain.Suspensions;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.AccountGuard.Activity;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 관리자(D-90)와 계정 정지(ADM-08)로 블로그 활동이 막히는지 (T018). */
class AccountGuardTest {

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 12, 0);

  private final AccountGuard guard =
      new AccountGuard(
          mock(UserRepository.class), Clock.fixed(ZonedDateTime.of(NOW, SEOUL).toInstant(), SEOUL));

  private static User user() {
    return new User("kim@example.com", "hash", "김", "kim", "01012345678", NOW.minusDays(1));
  }

  @Test
  void normalUserCanDoEverything() {
    for (Activity a : Activity.values()) {
      assertThatCode(() -> guard.check(user(), a)).doesNotThrowAnyException();
    }
  }

  @Test
  void adminCannotTakePartInBlogsButCanSubscribe() {
    User admin = user();
    ReflectionTestUtils.setField(admin, "role", UserRole.ADMIN);

    assertThatThrownBy(() -> guard.check(admin, Activity.WRITE_POST))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.ADMIN_NOT_ALLOWED);
    assertThatCode(() -> guard.check(admin, Activity.SUBSCRIBE)).doesNotThrowAnyException();
  }

  @Test
  void suspendedUserCanOnlyRead() {
    User suspended = user();
    ReflectionTestUtils.setField(suspended, "suspendedUntil", NOW.plusDays(3));

    for (Activity a : Activity.values()) {
      assertThatThrownBy(() -> guard.check(suspended, a))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("2026년 10월 11일 12:00까지");
    }
  }

  @Test
  void permanentSuspensionSaysPermanent() {
    User suspended = user();
    ReflectionTestUtils.setField(suspended, "suspendedUntil", Suspensions.PERMANENT);

    assertThatThrownBy(() -> guard.check(suspended, Activity.LIKE)).hasMessageContaining("영구");
  }

  @Test
  void endedSuspensionNoLongerBlocks() {
    User wasSuspended = user();
    ReflectionTestUtils.setField(wasSuspended, "suspendedUntil", NOW.minusMinutes(1));

    assertThatCode(() -> guard.check(wasSuspended, Activity.FOLLOW)).doesNotThrowAnyException();
  }
}
