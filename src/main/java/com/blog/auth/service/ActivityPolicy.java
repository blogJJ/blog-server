package com.blog.auth.service;

import com.blog.auth.domain.RefreshToken;
import com.blog.auth.repository.RefreshTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "로그인 유지"를 안 한 로그인의 30분 연장 규칙 (T036, SEC-04, D-62, D-72).
 *
 * <p>사용자가 직접 한 요청만 연장한다. 알림 30초 확인처럼 화면이 알아서 보내는 요청은 {@code X-Auto-Request: true} 헤더를 붙이고, 이 요청은 세지
 * 않는다. 그래야 탭만 열어 두고 자리를 떠도 30분 뒤 로그아웃된다. 글쓰기 중 입력은 session.js가 연장 요청을 보낸다.
 *
 * <p>DB 쓰기를 줄이려고 마지막 연장에서 1분이 지났을 때만 늘린다.
 */
@Component
public class ActivityPolicy {

  public static final String AUTO_REQUEST_HEADER = "X-Auto-Request";
  private static final Duration MIN_INTERVAL = Duration.ofMinutes(1);

  private final RefreshTokenRepository refreshTokenRepository;
  private final Clock clock;

  public ActivityPolicy(RefreshTokenRepository refreshTokenRepository, Clock clock) {
    this.refreshTokenRepository = refreshTokenRepository;
    this.clock = clock;
  }

  /** 사용자가 직접 한 요청인지 */
  public boolean isUserActivity(HttpServletRequest request) {
    return !"true".equalsIgnoreCase(request.getHeader(AUTO_REQUEST_HEADER));
  }

  /** 직접 한 요청이면 로그인 끝나는 시각을 지금부터 30분 뒤로 늘린다. "로그인 유지"한 로그인은 그대로 둔다. */
  @Transactional
  public void onRequest(RefreshToken session, HttpServletRequest request) {
    if (session.isRememberMe() || !isUserActivity(request)) {
      return;
    }
    extend(session);
  }

  /** 연장 요청(POST /api/auth/extend)처럼 무조건 늘릴 때 */
  @Transactional
  public void extend(RefreshToken session) {
    if (session.isRememberMe()) {
      return;
    }
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDateTime until = now.plus(LoginSessionService.IDLE_TIMEOUT);
    if (session.getExpiresAt().isAfter(until.minus(MIN_INTERVAL))) {
      return;
    }
    refreshTokenRepository.extend(session.getId(), now, until);
  }
}
