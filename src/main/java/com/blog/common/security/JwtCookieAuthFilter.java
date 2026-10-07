package com.blog.common.security;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 쿠키의 Access Token으로 로그인 상태를 만든다 (T015, SEC-04). 토큰은 HttpOnly 쿠키로만 받고 Authorization 헤더나
 * localStorage는 쓰지 않는다.
 *
 * <p>토큰이 맞아도 회원을 DB에서 다시 읽어, 탈퇴한 회원은 로그인되지 않게 하고 역할은 DB 값을 쓴다. 토큰이 없거나 틀리면 비회원으로 지나가고, 로그인이 필요한
 * 주소에서 401이 난다.
 *
 * <p>Spring 빈으로 등록하지 않는다(서블릿 필터로 두 번 걸리지 않게). SecurityConfig가 만든다.
 */
public class JwtCookieAuthFilter extends OncePerRequestFilter {

  public static final String ACCESS_TOKEN_COOKIE = "access_token";

  private final JwtProvider jwtProvider;
  private final UserRepository userRepository;

  public JwtCookieAuthFilter(JwtProvider jwtProvider, UserRepository userRepository) {
    this.jwtProvider = jwtProvider;
    this.userRepository = userRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = readCookie(request, ACCESS_TOKEN_COOKIE);
    jwtProvider
        .parseUserId(token)
        .flatMap(userRepository::findById)
        .filter(User::isActive)
        .ifPresent(this::authenticate);
    chain.doFilter(request, response);
  }

  private void authenticate(User user) {
    AuthUser principal = new AuthUser(user.getId(), user.getRole());
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private static String readCookie(HttpServletRequest request, String name) {
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
}
