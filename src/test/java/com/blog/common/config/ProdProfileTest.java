package com.blog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.support.MySqlTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** prod 프로필: https로 돌려보내고, 상태 확인은 예외이며, Swagger는 닫히고 쿠키는 Secure (T144, SC-011). */
@SpringBootTest(
    properties = {
      "spring.datasource.password=test-db-password",
      "spring.mail.password=test-mail-password",
      "app.turnstile.secret-key=test-turnstile-secret",
      "logging.file.path=build/test-logs"
    })
@AutoConfigureMockMvc
@ActiveProfiles({"test", "prod"})
@Import(MySqlTestcontainersConfig.class)
class ProdProfileTest {

  @Autowired MockMvc mvc;
  @Autowired CookieProperties cookieProperties;

  @Test
  void redirectsHttpToHttps() throws Exception {
    mvc.perform(get("http://blog.example.com/api/blogs"))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", "https://blog.example.com/api/blogs"));
  }

  @Test
  void redirectsServerPortToStandardHttpsPort() throws Exception {
    mvc.perform(get("http://blog.example.com:8080/api/blogs"))
        .andExpect(header().string("Location", "https://blog.example.com:443/api/blogs"));
  }

  @Test
  void healthIsNotRedirected() throws Exception {
    mvc.perform(get("http://blog.example.com/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void swaggerIsClosed() throws Exception {
    mvc.perform(get("https://blog.example.com/v3/api-docs")).andExpect(status().isNotFound());
  }

  @Test
  void cookieIsSecure() {
    assertThat(cookieProperties.secure()).isTrue();
  }
}
