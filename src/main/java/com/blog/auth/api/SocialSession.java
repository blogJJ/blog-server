package com.blog.auth.api;

import com.blog.auth.service.PendingSocialSignup;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Optional;

/**
 * 소셜 로그인 도중에만 서버 세션(JSESSIONID)에 두는 값 (T157, D-128). 로그인 자체는 세션을 쓰지 않고 JWT 쿠키로 한다(SEC-04). 세션은
 * 구글·깃허브에 다녀오는 동안 Spring의 state 값과 아래 값을 기억하는 데만 쓴다.
 *
 * <ul>
 *   <li>로그인 뒤 갈 주소(같은 사이트 경로만)와 "로그인 유지" 체크
 *   <li>내 정보 화면에서 시작한 연결이면 그 회원 번호
 *   <li>가입 마무리 전의 제공자 정보 ({@link PendingSocialSignup}, 10분)
 * </ul>
 */
final class SocialSession {

  static final String NEXT = "social.next";
  static final String REMEMBER_ME = "social.rememberMe";
  static final String LINK_USER_ID = "social.linkUserId";
  static final String PENDING = "social.pending";

  private SocialSession() {}

  static void startLogin(HttpServletRequest request, String next, boolean rememberMe) {
    HttpSession session = request.getSession();
    session.removeAttribute(LINK_USER_ID);
    session.removeAttribute(PENDING);
    session.setAttribute(NEXT, safePath(next));
    session.setAttribute(REMEMBER_ME, rememberMe);
  }

  static void startLink(HttpServletRequest request, Long userId) {
    HttpSession session = request.getSession();
    session.removeAttribute(PENDING);
    session.removeAttribute(NEXT);
    session.setAttribute(LINK_USER_ID, userId);
  }

  /** 로그인 뒤 갈 주소. 없으면 "/". 한 번 읽으면 지운다 */
  static String takeNext(HttpServletRequest request) {
    Object value = take(request, NEXT);
    return value instanceof String s ? s : "/";
  }

  static boolean takeRememberMe(HttpServletRequest request) {
    return Boolean.TRUE.equals(take(request, REMEMBER_ME));
  }

  /** 연결을 시작한 회원 번호. 로그인 화면에서 시작했으면 null */
  static Long takeLinkUserId(HttpServletRequest request) {
    return take(request, LINK_USER_ID) instanceof Long id ? id : null;
  }

  static void savePending(HttpServletRequest request, PendingSocialSignup pending) {
    request.getSession().setAttribute(PENDING, pending);
  }

  /** 지나지 않은 가입 마무리 정보 */
  static Optional<PendingSocialSignup> findPending(HttpServletRequest request, Instant now) {
    HttpSession session = request.getSession(false);
    if (session == null
        || !(session.getAttribute(PENDING) instanceof PendingSocialSignup pending)) {
      return Optional.empty();
    }
    if (pending.isExpiredAt(now)) {
      session.removeAttribute(PENDING);
      return Optional.empty();
    }
    return Optional.of(pending);
  }

  static void clearPending(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.removeAttribute(PENDING);
    }
  }

  /**
   * 다른 사이트로 보내지 않게 같은 사이트의 경로만 받는다. "//evil.com", "/\evil.com", "https://..."와 줄바꿈은 "/"로 바꾼다.
   * login.js의 nextUrl과 같은 규칙이다.
   */
  static String safePath(String next) {
    if (next == null
        || next.length() > 500
        || !next.startsWith("/")
        || next.startsWith("//")
        || next.startsWith("/\\")
        || next.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      return "/";
    }
    return next;
  }

  private static Object take(HttpServletRequest request, String name) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return null;
    }
    Object value = session.getAttribute(name);
    session.removeAttribute(name);
    return value;
  }
}
