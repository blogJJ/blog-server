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
 *
 * <p>401인데 요청에 로그인 쿠키(access_token)가 아예 없으면 {@link #NO_SESSION_HEADER} 헤더를 붙인다 (T164). 두 로그인 쿠키는 늘
 * 같이 쓰고 같이 지우므로 그때는 Refresh Token도 없다. 화면(api.js)은 이 헤더를 보면 토큰 재발급을 시도하지 않는다. 예전에는 비회원이 화면을 열 때마다
 * /api/me 401 뒤에 실패할 재발급 요청을 한 번 더 기다렸다.
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
    if (AuthCookies.read(request, AuthCookies.ACCESS_TOKEN) == null) {
      response.setHeader(NO_SESSION_HEADER, "none");
    }
    write(response, ErrorCode.UNAUTHORIZED);
  }

  /** 로그인 쿠키가 없어서 난 401에 붙는 헤더. 값은 {@code none}. */
  public static final String NO_SESSION_HEADER = "X-Auth-Session";

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
