package com.blog.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

  private static final String SECRET = "test-secret-for-jwt-signing-0123456789abcdef";
  private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

  private JwtProvider providerAt(Instant instant) {
    return new JwtProvider(
        new JwtProperties(SECRET, Duration.ofMinutes(15)),
        Clock.fixed(instant, ZoneId.of("Asia/Seoul")));
  }

  @Test
  void issuedTokenCarriesOnlyUserId() {
    JwtProvider provider = providerAt(NOW);

    String token = provider.createAccessToken(42L);

    assertThat(provider.parseUserId(token)).contains(42L);
  }

  @Test
  void expiredTokenIsRejected() {
    String token = providerAt(NOW).createAccessToken(42L);

    assertThat(providerAt(NOW.plus(Duration.ofMinutes(16))).parseUserId(token)).isEmpty();
  }

  @Test
  void tokenSignedWithAnotherKeyIsRejected() {
    JwtProvider other =
        new JwtProvider(
            new JwtProperties("another-secret-0123456789abcdef-0123456789", null),
            Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    assertThat(providerAt(NOW).parseUserId(other.createAccessToken(42L))).isEmpty();
  }

  @Test
  void malformedOrMissingTokenIsRejected() {
    JwtProvider provider = providerAt(NOW);

    assertThat(provider.parseUserId("not-a-jwt")).isEmpty();
    assertThat(provider.parseUserId("")).isEmpty();
    assertThat(provider.parseUserId(null)).isEmpty();
  }

  @Test
  void shortSecretFailsAtStartup() {
    assertThatThrownBy(
            () -> new JwtProvider(new JwtProperties("short", null), Clock.systemDefaultZone()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void accessTokenTtlDefaultsTo15Minutes() {
    assertThat(new JwtProperties(SECRET, null).accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
  }
}
