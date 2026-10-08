package com.blog.common.security;

import io.jsonwebtoken.Claims;
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
 * <p>토큰에는 회원 번호(subject)와 로그인 번호(sid, refresh_tokens.id)만 담는다. 역할·정지·블로그별 권한은 토큰을 믿지 않고 요청마다 DB에서
 * 확인한다 (SEC-07, research.md 3). 로그인 번호로 로그아웃·30분 무활동 만료를 바로 반영한다 (JwtCookieAuthFilter).
 */
@Component
public class JwtProvider {

  private static final int MIN_SECRET_BYTES = 32;
  private static final String SESSION_CLAIM = "sid";

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

  /**
   * Access Token을 만든다.
   *
   * @param notAfter 이 시각보다 늦게 끝나지 않게 한다(로그인이 끝나는 시각). null이면 기본 유효 시간
   */
  public String createAccessToken(long userId, long sessionId, Instant notAfter) {
    Instant now = clock.instant();
    Instant expiration = now.plus(accessTokenTtl);
    if (notAfter != null && notAfter.isBefore(expiration)) {
      expiration = notAfter;
    }
    return Jwts.builder()
        .subject(Long.toString(userId))
        .claim(SESSION_CLAIM, sessionId)
        .issuedAt(Date.from(now))
        .expiration(Date.from(expiration))
        .signWith(key)
        .compact();
  }

  /** 서명이 맞고 만료되지 않았으면 회원 번호와 로그인 번호를 돌려준다. 위조·만료·형식 오류·로그인 번호 없음은 모두 빈 값이다. */
  public Optional<AccessClaims> parse(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      Claims claims = parser.parseSignedClaims(token).getPayload();
      Number sessionId = claims.get(SESSION_CLAIM, Number.class);
      if (sessionId == null) {
        return Optional.empty();
      }
      return Optional.of(
          new AccessClaims(Long.valueOf(claims.getSubject()), sessionId.longValue()));
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /**
   * @param userId 회원 번호
   * @param sessionId 로그인 번호 (refresh_tokens.id)
   */
  public record AccessClaims(long userId, long sessionId) {}

  public Duration getAccessTokenTtl() {
    return accessTokenTtl;
  }
}
