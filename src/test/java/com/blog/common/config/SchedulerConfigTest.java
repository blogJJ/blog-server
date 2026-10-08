package com.blog.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ShedLock이 shedlock 테이블로 잠가서 같은 작업이 겹쳐 돌지 않는지 (T024, SCL-03). */
@IntegrationTest
class SchedulerConfigTest {

  @Autowired LockProvider lockProvider;

  @Test
  void secondServerCannotTakeLockUntilReleased() {
    LockConfiguration config =
        new LockConfiguration(
            Instant.now(), "test-" + UUID.randomUUID(), Duration.ofMinutes(1), Duration.ZERO);

    Optional<SimpleLock> first = lockProvider.lock(config);
    assertThat(first).isPresent();
    assertThat(lockProvider.lock(config)).isEmpty();

    first.get().unlock();
    Optional<SimpleLock> again = lockProvider.lock(config);
    assertThat(again).isPresent();
    again.get().unlock();
  }
}
