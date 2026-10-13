package com.blog.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * 소셜 로그인 뒤 제공자 Access Token을 보관하지 않는다 (T157, D-128). 회원 정보와 깃허브 이메일은 로그인하는 그 요청에서만 읽고, 그 뒤로 구글·깃허브
 * API를 부르지 않는다. Spring 기본값은 서버 메모리에 로그인마다 쌓아 둔다.
 */
class NoopAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

  @Override
  public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
      String clientRegistrationId, Authentication principal, HttpServletRequest request) {
    return null;
  }

  @Override
  public void saveAuthorizedClient(
      OAuth2AuthorizedClient authorizedClient,
      Authentication principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    // 제공자 토큰은 보관하지 않는다
  }

  @Override
  public void removeAuthorizedClient(
      String clientRegistrationId,
      Authentication principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    // 보관하지 않으므로 지울 것도 없다
  }
}
