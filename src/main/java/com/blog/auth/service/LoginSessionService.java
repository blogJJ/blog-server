package com.blog.auth.service;

import com.blog.auth.domain.RefreshToken;
import com.blog.auth.domain.User;
import com.blog.auth.repository.RefreshTokenRepository;
import com.blog.common.crypto.HashUtil;
import com.blog.common.security.JwtProvider;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 한 번(refresh_tokens 한 행)을 만들고, Access Token을 다시 발급하고, 끝낸다 (T035, T037, SEC-04, D-61).
 *
 * <ul>
 *   <li>"로그인 유지" 체크: 로그인한 때부터 14일.
 *   <li>미체크: 마지막 활동부터 30분. 활동하면 ActivityPolicy가 늘린다. Access Token도 로그인이 끝나는 시각보다 늦게 끝나지 않게 만든다.
 * </ul>
 *
 * <p>Refresh Token 원문은 쿠키로만 주고 DB에는 HMAC 해시만 둔다. 재발급 때 Refresh Token을 바꾸지 않는다. 탭 여러 개가 동시에 재발급해도 서로
 * 로그아웃시키지 않게 하려는 것이고, 대신 폐기·만료를 요청마다 DB로 확인한다.
 */
@Service
public class LoginSessionService {

  public static final Duration REMEMBER_ME_TTL = Duration.ofDays(14);
  public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

  private static final SecureRandom RANDOM = new SecureRandom();

  private final RefreshTokenRepository refreshTokenRepository;
  private final JwtProvider jwtProvider;
  private final HashUtil hashUtil;
  private final Clock clock;

  public LoginSessionService(
      RefreshTokenRepository refreshTokenRepository,
      JwtProvider jwtProvider,
      HashUtil hashUtil,
      Clock clock) {
    this.refreshTokenRepository = refreshTokenRepository;
    this.jwtProvider = jwtProvider;
    this.hashUtil = hashUtil;
    this.clock = clock;
  }

  /**
   * 새 로그인을 만든다.
   *
   * @param refreshToken 쿠키에 담을 원문. DB에는 없다
   * @param cookieMaxAge 쿠키 Max-Age. null이면 세션 쿠키
   */
  public record Issued(
      String accessToken, String refreshToken, Duration cookieMaxAge, RefreshToken session) {}

  @Transactional
  public Issued start(User user, boolean rememberMe, String userAgent) {
    LocalDateTime now = now();
    String raw = newToken();
    LocalDateTime expiresAt = now.plus(rememberMe ? REMEMBER_ME_TTL : IDLE_TIMEOUT);
    RefreshToken session =
        refreshTokenRepository.save(
            new RefreshToken(user, hashUtil.hash(raw), rememberMe, userAgent, expiresAt));
    return new Issued(accessTokenFor(session), raw, rememberMe ? REMEMBER_ME_TTL : null, session);
  }

  /** 쿠키의 Refresh Token으로 Access Token을 다시 만든다. 폐기·만료됐거나 탈퇴한 회원이면 빈 값. */
  @Transactional(readOnly = true)
  public Optional<Issued> refresh(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      return Optional.empty();
    }
    LocalDateTime now = now();
    return refreshTokenRepository
        .findByTokenHash(hashUtil.hash(rawRefreshToken))
        .filter(s -> s.isActiveAt(now))
        .filter(s -> s.getUser().isActive())
        .map(
            s ->
                new Issued(
                    accessTokenFor(s), null, s.isRememberMe() ? remainingOf(s, now) : null, s));
  }

  /** Access Token의 로그인 번호로 살아 있는 로그인을 찾는다 (JwtCookieAuthFilter). 회원도 함께 읽는다. */
  @Transactional(readOnly = true)
  public Optional<RefreshToken> findActive(long sessionId) {
    LocalDateTime now = now();
    return refreshTokenRepository.findWithUserById(sessionId).filter(s -> s.isActiveAt(now));
  }

  /** 로그아웃: 쿠키의 Refresh Token에 해당하는 로그인을 폐기한다. 없거나 이미 폐기됐으면 아무것도 하지 않는다. */
  @Transactional
  public void revoke(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      return;
    }
    refreshTokenRepository
        .findByTokenHash(hashUtil.hash(rawRefreshToken))
        .ifPresent(s -> s.revoke(now()));
  }

  /** Access Token의 로그인 번호로 폐기한다. Refresh 쿠키가 없을 때 로그아웃용. */
  @Transactional
  public void revoke(long sessionId) {
    refreshTokenRepository.findById(sessionId).ifPresent(s -> s.revoke(now()));
  }

  /** 그 회원의 로그인을 모두 끝낸다 (비밀번호 변경·탈퇴, SEC-04). */
  @Transactional
  public void revokeAll(long userId) {
    refreshTokenRepository.revokeAllOf(userId, now());
  }

  private String accessTokenFor(RefreshToken session) {
    return jwtProvider.createAccessToken(
        session.getUser().getId(),
        session.getId(),
        session.getExpiresAt().atZone(clock.getZone()).toInstant());
  }

  private static Duration remainingOf(RefreshToken session, LocalDateTime now) {
    return Duration.between(now, session.getExpiresAt());
  }

  private static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
