package com.blog.auth.service;

import com.blog.auth.domain.SocialProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * OAuth2(OIDC 아닌) 제공자의 회원 정보를 읽는다 (T157, D-128). 지금은 깃허브만: /user를 읽고, /user/emails의 인증된 대표 이메일을
 * {@link SocialProfile#GITHUB_VERIFIED_EMAIL}로 더한다. 구글은 OIDC라 Spring 기본 OidcUserService가 읽는다.
 */
@Component
public class SocialOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

  private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
  private final GithubEmailClient githubEmailClient;

  @Autowired
  public SocialOAuth2UserService(GithubEmailClient githubEmailClient) {
    this(new DefaultOAuth2UserService(), githubEmailClient);
  }

  SocialOAuth2UserService(
      OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate,
      GithubEmailClient githubEmailClient) {
    this.delegate = delegate;
    this.githubEmailClient = githubEmailClient;
  }

  @Override
  public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
    OAuth2User user = delegate.loadUser(request);
    String registrationId = request.getClientRegistration().getRegistrationId();
    if (user == null || !SocialProvider.GITHUB.getId().equals(registrationId)) {
      return user; // null이면 이 제공자를 다루지 않는다는 뜻(OAuth2UserService 약속)이라 그대로 넘긴다
    }
    Map<String, Object> attrs = new HashMap<>(user.getAttributes());
    String email = githubEmailClient.primaryVerifiedEmail(request.getAccessToken().getTokenValue());
    if (email != null) {
      attrs.put(SocialProfile.GITHUB_VERIFIED_EMAIL, email);
    } else {
      attrs.remove(SocialProfile.GITHUB_VERIFIED_EMAIL);
    }
    String nameKey =
        request
            .getClientRegistration()
            .getProviderDetails()
            .getUserInfoEndpoint()
            .getUserNameAttributeName();
    // delegate(DefaultOAuth2UserService)가 이 이름이 없으면 이미 실패하므로 여기서는 늘 있다
    Objects.requireNonNull(nameKey, "user-name-attribute");
    return new DefaultOAuth2User(user.getAuthorities(), attrs, nameKey);
  }
}
