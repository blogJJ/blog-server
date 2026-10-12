package com.blog.auth.api;

import com.blog.auth.service.SocialClientRegistrations;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * {@code GET /oauth2/authorization/{google|github}}에서 구글·깃허브로 보내기 전에 로그인 뒤 갈 주소와 "로그인 유지"를 세션에 적어
 * 둔다 (T157, D-128). state 값(구글은 nonce도)은 Spring 기본 resolver가 만든다.
 *
 * <ul>
 *   <li>{@code ?next=/경로}: 같은 사이트 경로만 받는다.
 *   <li>{@code ?remember=1}: 로그인 화면의 "로그인 유지" 체크.
 *   <li>{@code ?link=1}: 내 정보 화면의 연결. 먼저 POST /api/me/social/{provider}/link(CSRF 확인)로 세션에 회원 번호를
 *       적어 둔 경우에만 연결로 본다. link가 없으면 남아 있던 연결 표시를 지운다.
 * </ul>
 */
@Component
public class SocialAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

  private static final String BASE_URI =
      OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI + "/";

  private final SocialClientRegistrations registrations;
  private final DefaultOAuth2AuthorizationRequestResolver delegate;

  public SocialAuthorizationRequestResolver(SocialClientRegistrations registrations) {
    this.registrations = registrations;
    this.delegate =
        new DefaultOAuth2AuthorizationRequestResolver(
            registrations,
            OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    // 꺼진 제공자 주소는 500(Invalid Client Registration) 대신 그냥 지나가 404가 되게 한다
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (path.startsWith(BASE_URI)
        && registrations.findByRegistrationId(path.substring(BASE_URI.length())) == null) {
      return null;
    }
    return remember(request, delegate.resolve(request));
  }

  @Override
  public OAuth2AuthorizationRequest resolve(
      HttpServletRequest request, String clientRegistrationId) {
    return remember(request, delegate.resolve(request, clientRegistrationId));
  }

  private static OAuth2AuthorizationRequest remember(
      HttpServletRequest request, OAuth2AuthorizationRequest authorizationRequest) {
    if (authorizationRequest == null) {
      return null;
    }
    boolean link = "1".equals(request.getParameter("link"));
    if (!link) {
      SocialSession.startLogin(
          request, request.getParameter("next"), "1".equals(request.getParameter("remember")));
    }
    return authorizationRequest;
  }
}
