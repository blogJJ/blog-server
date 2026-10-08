package com.blog.common.security;

import com.blog.common.config.CookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 로그인 쿠키 두 개를 쓰고 지운다 (SEC-04). 둘 다 HttpOnly라 화면 JS가 읽을 수 없고, Secure는 app.cookie.secure(local만 끔)를
 * 따른다.
 *
 * <ul>
 *   <li>{@code access_token}: 짧은 JWT. 모든 주소에 간다.
 *   <li>{@code refresh_token}: Access Token을 다시 받을 때만 쓰므로 /api/auth 아래로만 간다.
 * </ul>
 *
 * <p>"로그인 유지"를 안 하면 Max-Age 없는 세션 쿠키라 브라우저를 닫으면 사라진다. 하면 14일 남는다 (D-61).
 */
@Component
public class AuthCookies {

  public static final String ACCESS_TOKEN = "access_token";
  public static final String REFRESH_TOKEN = "refresh_token";
  static final String REFRESH_PATH = "/api/auth";

  private final boolean secure;

  public AuthCookies(CookieProperties properties) {
    this.secure = properties.secure();
  }

  /** maxAge가 null이면 세션 쿠키 */
  public void write(
      HttpServletResponse response, String accessToken, String refreshToken, Duration maxAge) {
    if (refreshToken != null) {
      add(response, REFRESH_TOKEN, refreshToken, REFRESH_PATH, "Strict", maxAge);
    }
    add(response, ACCESS_TOKEN, accessToken, "/", "Lax", maxAge);
  }

  public void clear(HttpServletResponse response) {
    add(response, ACCESS_TOKEN, "", "/", "Lax", Duration.ZERO);
    add(response, REFRESH_TOKEN, "", REFRESH_PATH, "Strict", Duration.ZERO);
  }

  public static String read(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (name.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  private void add(
      HttpServletResponse response,
      String name,
      String value,
      String path,
      String sameSite,
      Duration maxAge) {
    ResponseCookie.ResponseCookieBuilder cookie =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(secure)
            .path(path)
            .sameSite(sameSite);
    if (maxAge != null) {
      cookie.maxAge(maxAge);
    }
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
  }
}
