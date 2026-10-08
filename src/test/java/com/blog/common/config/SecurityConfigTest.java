package com.blog.common.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.auth.service.LoginSessionService;
import com.blog.common.security.JwtCookieAuthFilter;
import com.blog.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 주소별 권한, CSRF, 쿠키 JWT, 보안 헤더 (T015, T016, SEC-04, SEC-10~12). */
@IntegrationTest
class SecurityConfigTest {

  @Autowired MockMvc mockMvc;
  @Autowired LoginSessionService sessionService;
  @Autowired UserRepository userRepository;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void writeWithoutLoginIs401Json() throws Exception {
    mockMvc
        .perform(post("/api/posts").with(csrf()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void writeWithoutCsrfTokenIs403() throws Exception {
    mockMvc
        .perform(post("/api/posts").cookie(tokenCookie(newUser("csrf"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void loggedInUserPassesSecurityAndReachesMvc() throws Exception {
    // 아직 컨트롤러가 없으니 보안을 통과하면 404가 나온다
    mockMvc
        .perform(post("/api/posts").with(csrf()).cookie(tokenCookie(newUser("writer"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void adminApiIsForbiddenForUsersAndOpenForAdmins() throws Exception {
    mockMvc
        .perform(get("/api/admin/reports").cookie(tokenCookie(newUser("plain"))))
        .andExpect(status().isForbidden());

    User admin = newUser("boss");
    jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
    mockMvc
        .perform(get("/api/admin/reports").cookie(tokenCookie(admin)))
        .andExpect(status().isNotFound());
  }

  @Test
  void myPagesNeedLoginEvenForGet() throws Exception {
    mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
  }

  @Test
  void withdrawnUserTokenIsNotAccepted() throws Exception {
    User user = newUser("gone");
    jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN' WHERE id = ?", user.getId());

    mockMvc
        .perform(post("/api/posts").with(csrf()).cookie(tokenCookie(user)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void forgedTokenIsTreatedAsGuest() throws Exception {
    mockMvc
        .perform(
            post("/api/posts")
                .with(csrf())
                .cookie(new Cookie(JwtCookieAuthFilter.ACCESS_TOKEN_COOKIE, "forged.token.value")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void publicReadIsOpenAndSendsSecurityHeaders() throws Exception {
    mockMvc
        .perform(get("/api/blogs"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
  }

  @Test
  void unknownNonGetRequestOutsideApiIsDenied() throws Exception {
    mockMvc.perform(post("/somewhere").with(csrf())).andExpect(status().isUnauthorized());
  }

  private User newUser(String nickname) {
    return userRepository.save(
        new User(
            nickname + "@example.com",
            "$2a$10$hash",
            "이름",
            nickname,
            "01012345678",
            LocalDateTime.of(2026, 10, 7, 9, 0)));
  }

  private Cookie tokenCookie(User user) {
    return new Cookie(
        JwtCookieAuthFilter.ACCESS_TOKEN_COOKIE,
        sessionService.start(user, false, "test").accessToken());
  }
}
