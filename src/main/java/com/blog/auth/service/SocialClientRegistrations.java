package com.blog.auth.service;

import com.blog.auth.domain.SocialProvider;
import com.blog.common.config.SocialLoginProperties;
import com.blog.common.config.SocialLoginProperties.Client;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>돌아오는 주소는 {@code {baseUrl}/login/oauth2/code/{google|github}}. baseUrl은 요청에서 만들고, 운영에서는 프록시가 보낸
 * X-Forwarded-Proto·Host를 따른다(forward-headers-strategy native).
 */
@Component
public class SocialClientRegistrations implements ClientRegistrationRepository {

  private static final Logger log = LoggerFactory.getLogger(SocialClientRegistrations.class);

  private final Map<SocialProvider, ClientRegistration> registrations =
      new EnumMap<>(SocialProvider.class);

  public SocialClientRegistrations(SocialLoginProperties properties) {
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
