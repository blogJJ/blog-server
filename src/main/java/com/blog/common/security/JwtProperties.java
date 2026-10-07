package com.blog.common.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정 (app.jwt). secret은 환경변수 JWT_SECRET으로 받는다.
 *
 * @param secret HMAC-SHA 서명 키. 32바이트 이상
 * @param accessTokenTtl Access Token 유효 시간. 짧게 두고 만료되면 Refresh Token으로 다시 받는다 (SEC-04)
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl) {

  public JwtProperties {
    if (accessTokenTtl == null) {
      accessTokenTtl = Duration.ofMinutes(15);
    }
  }
}
