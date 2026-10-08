package com.blog.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 운영(prod)에서 비밀값이 비었거나 예시 값이면 서버를 멈춘다 (T144, OPS-11, D-111).
 *
 * <p>비밀키 없이 운영 서버가 뜨면 누구나 아는 키로 로그인 토큰을 위조할 수 있어서, 아예 뜨지 않게 한다. 오류 메시지에는 빠진 환경변수 이름만 적고 값은 적지 않는다.
 */
@Component
@Profile("prod")
public class ProdSecretsCheck {

  /** 설정 키 → 그 값을 넣는 환경변수 이름. */
  static final Map<String, String> REQUIRED_SECRETS = new LinkedHashMap<>();

  static {
    REQUIRED_SECRETS.put("app.jwt.secret", "JWT_SECRET");
    REQUIRED_SECRETS.put("spring.datasource.password", "DB_PASSWORD");
    REQUIRED_SECRETS.put("spring.mail.password", "MAIL_PASSWORD");
    REQUIRED_SECRETS.put("app.turnstile.secret-key", "TURNSTILE_SECRET_KEY");
  }

  /** application.yml 기본값과 .env.example에 적힌 예시 값. 운영에서 쓰면 안 된다. */
  static final Set<String> EXAMPLE_VALUES = Set.of("blog", "root", "changeme", "secret");

  public ProdSecretsCheck(Environment env) {
    List<String> invalid = findInvalid(env);
    if (!invalid.isEmpty()) {
      throw new IllegalStateException(
          "prod 프로필에 비밀값이 없거나 예시 값입니다. 환경변수를 확인하세요: " + String.join(", ", invalid));
    }
  }

  static List<String> findInvalid(Environment env) {
    List<String> invalid = new ArrayList<>();
    REQUIRED_SECRETS.forEach(
        (key, envName) -> {
          String value = env.getProperty(key);
          if (value == null || value.isBlank() || EXAMPLE_VALUES.contains(value.trim())) {
            invalid.add(envName);
          }
        });
    return invalid;
  }
}
