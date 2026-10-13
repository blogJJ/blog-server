package com.blog.auth.service;

import com.blog.auth.domain.SocialProvider;
import com.blog.common.config.SocialLoginProperties;
import com.blog.common.config.SocialLoginProperties.Client;
import com.blog.common.web.SiteUrl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * 켜진 소셜 로그인 제공자 목록 (T157, D-128). Spring Security OAuth2 로그인이 이 목록으로 구글·깃허브에 보낸다.
 *
 * <p>Spring Boot의 spring.security.oauth2.client.registration 설정은 아이디가 비면 서버가 뜨지 않아서 쓰지 않고, 아이디와
 * 비밀값이 모두 있는 제공자만 여기서 등록한다. 하나도 없으면 비어 있고, SecurityConfig가 소셜 로그인 주소를 열지 않는다.
 *
 * <p>돌아오는 주소는 {@code {사이트 주소}/login/oauth2/code/{google|github}}. 사이트 주소({@code app.site-url})를 정해
 * 두었으면(prod: https://jj-blog.java21.net) 그 값을 쓰고, 없으면(local·test) 요청에서 만든다 (T160).
 */
@Component
public class SocialClientRegistrations implements ClientRegistrationRepository {

  private static final Logger log = LoggerFactory.getLogger(SocialClientRegistrations.class);

  private final Map<SocialProvider, ClientRegistration> registrations =
      new EnumMap<>(SocialProvider.class);

  /** 돌아오는 주소. 사이트 주소가 없으면 Spring 기본값({baseUrl}은 요청에서 만든다) */
  private final String redirectUri;

  public SocialClientRegistrations(SocialLoginProperties properties) {
    this(properties, (String) null);
  }

  @Autowired
  public SocialClientRegistrations(SocialLoginProperties properties, SiteUrl siteUrl) {
    this(properties, siteUrl.configured());
  }

  SocialClientRegistrations(SocialLoginProperties properties, String siteUrl) {
    this.redirectUri = siteUrl == null ? null : siteUrl + "/login/oauth2/code/{registrationId}";
    add(SocialProvider.GOOGLE, properties.google());
    add(SocialProvider.GITHUB, properties.github());
  }

  private void add(SocialProvider provider, Client client) {
    if (client == null || !client.configured()) {
      return;
    }
    if (!client.enabled()) {
      // 값은 남기지 않고 무엇이 빠졌는지만 알린다
      log.warn("{} social login disabled: client secret is empty", provider.getId());
      return;
    }
    ClientRegistration.Builder builder =
        switch (provider) {
          // openid·profile·email. email_verified를 함께 받는다
          case GOOGLE -> CommonOAuth2Provider.GOOGLE.getBuilder(provider.getId());
          // 공개하지 않은 이메일과 인증 여부는 /user/emails로 읽어야 해서 user:email을 더한다
          case GITHUB ->
              CommonOAuth2Provider.GITHUB
                  .getBuilder(provider.getId())
                  .scope("read:user", "user:email");
        };
    if (redirectUri != null) {
      builder.redirectUri(redirectUri);
    }
    registrations.put(
        provider,
        builder.clientId(client.clientId().strip()).clientSecret(client.clientSecret()).build());
  }

  @Override
  public ClientRegistration findByRegistrationId(String registrationId) {
    return SocialProvider.fromId(registrationId).map(registrations::get).orElse(null);
  }

  /** 켜진 제공자. 화면이 이 목록의 버튼만 보여 준다 */
  public List<SocialProvider> enabledProviders() {
    return Collections.unmodifiableList(new ArrayList<>(registrations.keySet()));
  }

  public boolean isEnabled(SocialProvider provider) {
    return registrations.containsKey(provider);
  }

  public boolean isEmpty() {
    return registrations.isEmpty();
  }
}
