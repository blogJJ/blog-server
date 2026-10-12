package com.blog.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 소셜 로그인 클라이언트 키 (T157, D-128). GOOGLE_CLIENT_ID·GOOGLE_CLIENT_SECRET,
 * GH_CLIENT_ID·GH_CLIENT_SECRET 환경변수로 넣는다. 아이디와 비밀값이 모두 있는 제공자만 켜지고, 하나도 없어도 서버는 뜬다(소셜 로그인 버튼만
 * 숨는다).
 *
 * @param google 구글 OAuth 클라이언트. 없으면 null
 * @param github 깃허브 OAuth App. 없으면 null
 */
@ConfigurationProperties("app.social")
public record SocialLoginProperties(Client google, Client github) {

  /**
   * @param clientId 클라이언트 아이디. 비어 있으면 그 제공자를 끈다
   * @param clientSecret 클라이언트 비밀값. 로그에 남기지 않는다
   */
  public record Client(String clientId, String clientSecret) {

    public boolean configured() {
      return clientId != null && !clientId.isBlank();
    }

    public boolean enabled() {
      return configured() && clientSecret != null && !clientSecret.isBlank();
    }

    @Override
    public String toString() {
      return "Client[clientId=" + clientId + ", clientSecret=***]";
    }
  }
}
