package com.blog.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/** 통합 테스트용 MySQL 8.4. docker-compose.yml과 같은 문자셋·ngram 설정으로 띄운다. Docker가 있어야 한다. */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestcontainersConfig {

  @Bean
  @ServiceConnection
  MySQLContainer mysql() {
    return new MySQLContainer("mysql:8.4")
        .withCommand(
            "--character-set-server=utf8mb4",
            "--collation-server=utf8mb4_unicode_ci",
            "--default-time-zone=+09:00",
            "--ngram-token-size=2");
  }
}
