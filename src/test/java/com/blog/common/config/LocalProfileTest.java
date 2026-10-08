package com.blog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.support.MySqlTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** local 프로필에서만 Swagger가 열리고 쿠키 Secure가 꺼지고 시험용 회원이 들어가는지 (T144, T145, OPS-11, OPS-12). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@Import(MySqlTestcontainersConfig.class)
class LocalProfileTest {

  @Autowired MockMvc mvc;
  @Autowired CookieProperties cookieProperties;
  @Autowired UserRepository userRepository;
  @Autowired PasswordEncoder passwordEncoder;

  @Test
  void swaggerIsOpen() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openapi").exists());
  }

  @Test
  void cookieIsNotSecureOnLocalhost() {
    assertThat(cookieProperties.secure()).isFalse();
  }

  @Test
  void seedUsersCanLogInWithTestPassword() {
    User admin = userRepository.findByEmail("admin@example.com").orElseThrow();
    assertThat(passwordEncoder.matches("test1234!", admin.getPasswordHash())).isTrue();
    assertThat(userRepository.findByEmail("user19@example.com").orElseThrow().getSuspendedUntil())
        .isNotNull();
    assertThat(userRepository.existsByNickname("다은")).isTrue();
  }
}
