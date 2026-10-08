package com.blog.common.config;

import com.blog.auth.repository.UserRepository;
import com.blog.common.security.JsonSecurityErrorHandler;
import com.blog.common.security.JwtCookieAuthFilter;
import com.blog.common.security.JwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security 설정 (T016, SEC-10~12, constitution I).
 *
 * <ul>
 *   <li>로그인: 세션을 만들지 않고 쿠키의 JWT로 확인한다 (SEC-04).
 *   <li>CSRF: 토큰을 XSRF-TOKEN 쿠키로 주고, POST·PUT·PATCH·DELETE에서 X-XSRF-TOKEN 헤더로 받는다. 공통 api.js(T027)가
 *       붙인다 (SEC-10).
 *   <li>주소 권한: 비회원·회원·관리자만 여기서 나눈다. 블로그별 역할은 {@code @PreAuthorize("@blogAuthz...")}로 메서드에서 확인한다
 *       (SEC-11, T017).
 *   <li>보안 헤더: X-Frame-Options DENY, nosniff, CSP, HSTS(HTTPS일 때), Referrer-Policy (SEC-12).
 *   <li>HTTPS: {@code app.security.require-https=true}(prod)면 http 요청을 https로 돌려보낸다. 로드밸런서가 http로
 *       묻는 상태 확인 주소만 예외 (SEC-10, OPS-11).
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  /**
   * 화면은 정적 HTML + JS(D-66). 스크립트·스타일은 우리 서버에서만, Turnstile(SEC-13)만 예외. 글 본문 이미지는 우리 서버 주소라 'self'로
   * 충분하다.
   */
  static final String CONTENT_SECURITY_POLICY =
      "default-src 'self'; "
          + "script-src 'self' https://challenges.cloudflare.com; "
          + "frame-src https://challenges.cloudflare.com; "
          + "style-src 'self' 'unsafe-inline'; "
          + "img-src 'self' data: blob:; "
          + "object-src 'none'; "
          + "base-uri 'self'; "
          + "form-action 'self'; "
          + "frame-ancestors 'none'";

  static final String HEALTH_PATH = "/actuator/health";

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtProvider jwtProvider,
      UserRepository userRepository,
      JsonMapper jsonMapper,
      @Value("${app.security.require-https:false}") boolean requireHttps)
      throws Exception {
    JsonSecurityErrorHandler errorHandler = new JsonSecurityErrorHandler(jsonMapper);

    if (requireHttps) {
      // 서버는 8080으로 받고 사용자에게는 443(https 기본 포트)으로 보낸다. 기본 매핑은 8080 → 8443이라 바꾼다
      http.portMapper(ports -> ports.http(8080).mapsTo(443));
      http.redirectToHttps(
          https ->
              https.requestMatchers(
                  new NegatedRequestMatcher(
                      PathPatternRequestMatcher.withDefaults().matcher(HEALTH_PATH))));
    }

    http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.spa())
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .headers(
            headers ->
                headers
                    .frameOptions(frame -> frame.deny())
                    .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                    .referrerPolicy(
                        referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(errorHandler).accessDeniedHandler(errorHandler))
        .authorizeHttpRequests(
            auth ->
                auth
                    // 서버 상태 확인은 누구나, 다른 Actuator 주소는 막는다 (OPS-04, T138)
                    .requestMatchers(HttpMethod.GET, HEALTH_PATH)
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .denyAll()
                    // 가입·로그인·토큰 재발급은 비회원도 (USR-01~03)
                    .requestMatchers("/api/auth/**")
                    .permitAll()
                    // 메인 관리자 (ADM)
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    // 내 정보·알림은 읽기도 로그인 필요 (USR-07, SOC-04)
                    .requestMatchers("/api/me/**", "/api/notifications/**")
                    .authenticated()
                    // 그 밖의 읽기는 비회원도. 비공개·일부 공개 블로그는 서비스가 막는다 (BLG-01, T047)
                    .requestMatchers(HttpMethod.GET, "/api/**")
                    .permitAll()
                    // 그 밖의 API(쓰기)는 로그인 필요
                    .requestMatchers("/api/**")
                    .authenticated()
                    // 화면(정적 HTML·JS·CSS)과 오류 화면
                    .requestMatchers(HttpMethod.GET, "/**")
                    .permitAll()
                    .requestMatchers("/error")
                    .permitAll()
                    // 정하지 않은 요청은 막는다. 새 주소는 여기에 규칙을 더한다
                    .anyRequest()
                    .denyAll())
        .addFilterBefore(
            new JwtCookieAuthFilter(jwtProvider, userRepository),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /** 비밀번호는 bcrypt로 저장한다 (SEC-01). */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
