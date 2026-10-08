package com.blog.common.security;

import com.blog.auth.domain.RefreshToken;
import com.blog.auth.domain.User;
import com.blog.auth.service.ActivityPolicy;
import com.blog.auth.service.LoginSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
 * <p>토큰이 맞아도 로그인(refresh_tokens 행)과 회원을 DB에서 다시 읽는다. 로그아웃·비밀번호 변경으로 폐기됐거나, 30분 무활동으로 끝났거나, 탈퇴한 회원이면
 * 로그인되지 않는다. 역할은 DB 값을 쓴다. 로그인되면 ActivityPolicy로 30분을 늘린다(T036). 토큰이 없거나 틀리면 비회원으로 지나가고, 로그인이 필요한
 * 주소에서 401이 난다.
 *
 * <p>Spring 빈으로 등록하지 않는다(서블릿 필터로 두 번 걸리지 않게). SecurityConfig가 만든다.
 */
public class JwtCookieAuthFilter extends OncePerRequestFilter {

  public static final String ACCESS_TOKEN_COOKIE = AuthCookies.ACCESS_TOKEN;

  private final JwtProvider jwtProvider;
  private final LoginSessionService sessionService;
  private final ActivityPolicy activityPolicy;

  public JwtCookieAuthFilter(
      JwtProvider jwtProvider, LoginSessionService sessionService, ActivityPolicy activityPolicy) {
    this.jwtProvider = jwtProvider;
    this.sessionService = sessionService;
    this.activityPolicy = activityPolicy;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    jwtProvider
        .parse(AuthCookies.read(request, ACCESS_TOKEN_COOKIE))
        .flatMap(
            claims ->
                sessionService
                    .findActive(claims.sessionId())
                    .filter(s -> s.getUser().getId() == claims.userId()))
        .filter(s -> s.getUser().isActive())
        .ifPresent(
            session -> {
              authenticate(session.getUser(), session);
              activityPolicy.onRequest(session, request);
            });
    chain.doFilter(request, response);
  }

  private void authenticate(User user, RefreshToken session) {
    AuthUser principal = new AuthUser(user.getId(), user.getRole(), session.getId());
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
