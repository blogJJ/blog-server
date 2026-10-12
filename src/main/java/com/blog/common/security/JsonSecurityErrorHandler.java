package com.blog.common.security;

import com.blog.common.error.ErrorCode;
import com.blog.common.error.ErrorResponse;
import com.blog.common.logging.SecurityEventLogger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security 필터 단계에서 막힌 요청(로그인 필요 401, 권한 없음·CSRF 실패 403)도 API와 같은 {code, message}로 답한다 (OPS-02).
 *
 * <p>관리자 화면({@code /admin/**})은 사람이 주소창으로 여는 화면이라 JSON 대신 401은 로그인 화면으로 보내고, 403은 오류 화면을 보여 준다
 * (T126).
 */
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final JsonMapper jsonMapper;

  public JsonSecurityErrorHandler(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    if (isAdminPage(request)) {
      response.sendRedirect("/login.html?next=" + ADMIN_HOME);
      return;
    }
    write(response, ErrorCode.UNAUTHORIZED);
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    SecurityEventLogger.accessDenied(request);
    if (isAdminPage(request)) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    write(response, ErrorCode.FORBIDDEN);
  }

  static final String ADMIN_HOME = "/admin/";

  private static boolean isAdminPage(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return "GET".equals(request.getMethod())
        && (path.equals("/admin") || path.startsWith("/admin/"));
  }

  private void write(HttpServletResponse response, ErrorCode code) throws IOException {
    response.setStatus(code.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    jsonMapper.writeValue(response.getWriter(), ErrorResponse.of(code));
  }
}
