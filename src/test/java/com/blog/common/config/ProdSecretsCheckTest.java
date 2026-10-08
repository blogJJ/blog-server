package com.blog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProdSecretsCheckTest {

  private static MockEnvironment allSecrets() {
    return new MockEnvironment()
        .withProperty("app.jwt.secret", "prod-secret-0123456789abcdef0123456789")
        .withProperty("app.hash.secret", "prod-hash-secret-0123456789abcdef01234")
        .withProperty("spring.datasource.password", "s3cure-db")
        .withProperty("spring.mail.password", "gmail-app-password")
        .withProperty("app.turnstile.secret-key", "turnstile-secret");
  }

  @Test
  void passesWhenAllSecretsAreSet() {
    assertThat(ProdSecretsCheck.findInvalid(allSecrets())).isEmpty();
  }

  @Test
  void stopsStartupWhenJwtSecretIsMissing() {
    MockEnvironment env = allSecrets().withProperty("app.jwt.secret", "");

    assertThatThrownBy(() -> new ProdSecretsCheck(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET")
        .hasMessageNotContaining("s3cure-db");
  }

  @Test
  void rejectsExampleDbPassword() {
    MockEnvironment env = allSecrets().withProperty("spring.datasource.password", "blog");

    assertThat(ProdSecretsCheck.findInvalid(env)).containsExactly("DB_PASSWORD");
  }

  @Test
  void listsEveryMissingSecret() {
    assertThat(ProdSecretsCheck.findInvalid(new MockEnvironment()))
        .containsExactly(
            "JWT_SECRET", "HASH_SECRET", "DB_PASSWORD", "MAIL_PASSWORD", "TURNSTILE_SECRET_KEY");
  }
}
