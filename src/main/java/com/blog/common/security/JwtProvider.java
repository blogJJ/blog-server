package com.blog.common.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Access Token(JWT) 발급과 검증 (T015, SEC-04).
 *
 * <p>토큰에는 회원 번호(subject)만 담는다. 역할·정지·블로그별 권한은 토큰을 믿지 않고 요청마다 DB에서 확인한다 (SEC-07, research.md 3).
 */
@Component
public class JwtProvider {

  private static final int MIN_SECRET_BYTES = 32;

  private final SecretKey key;
  private final Duration accessTokenTtl;
  private final Clock clock;
  private final JwtParser parser;

  public JwtProvider(JwtProperties properties, Clock clock) {
    String secret = properties.secret();
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes (see .env.example)");
    }
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.accessTokenTtl = properties.accessTokenTtl();
    this.clock = clock;
    this.parser = Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build();
  }

  public String createAccessToken(long userId) {
    Instant now = clock.instant();
    return Jwts.builder()
        .subject(Long.toString(userId))
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(accessTokenTtl)))
        .signWith(key)
        .compact();
  }

  /** 서명이 맞고 만료되지 않았으면 회원 번호를 돌려준다. 위조·만료·형식 오류는 모두 빈 값이다. */
  public Optional<Long> parseUserId(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      String subject = parser.parseSignedClaims(token).getPayload().getSubject();
      return Optional.of(Long.valueOf(subject));
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  public Duration getAccessTokenTtl() {
    return accessTokenTtl;
  }
}
