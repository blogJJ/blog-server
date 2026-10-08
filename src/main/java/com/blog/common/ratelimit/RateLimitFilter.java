package com.blog.common.ratelimit;

import com.blog.common.error.ErrorCode;
import com.blog.common.error.ErrorResponse;
import com.blog.common.logging.SecurityEventLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모든 API({@code /api/**})에 IP별 기본 요청 제한을 건다 (T022, SEC-03, D-80). 넘으면 429와 Retry-After를 주고 security
 * 로그에 남긴다(OPS-03).
 *
 * <p>IP는 {@code request.getRemoteAddr()}다. 프록시 뒤에서 X-Forwarded-For를 믿을지는 {@code
 * server.forward-headers-strategy}(FORWARD_HEADERS_STRATEGY) 설정으로 정한다. 믿지 않는 설정이면 클라이언트가 보낸 헤더로 IP를
 * 바꿔 제한을 피할 수 없다. 로그인·인증번호처럼 더 좁은 제한은 각 기능에서 {@link RateLimiter}를 직접 부른다.
 *
 * <p>로그인 확인보다 먼저 돌아서 막힌 요청은 DB에 닿지 않는다. 요청 ID 필터 다음이라 로그에 요청 ID가 붙는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

  static final String LIMIT_NAME = "api";
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final RateLimiter rateLimiter;
  private final JsonMapper jsonMapper;
  private final int perMinute;

  public RateLimitFilter(
      RateLimiter rateLimiter,
      JsonMapper jsonMapper,
      @Value("${app.rate-limit.api-per-minute:300}") int perMinute) {
    this.rateLimiter = rateLimiter;
    this.jsonMapper = jsonMapper;
    this.perMinute = perMinute;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String ip = request.getRemoteAddr();
    RateLimiter.Decision decision =
        rateLimiter.tryAcquire(LIMIT_NAME + ":" + ip, perMinute, WINDOW);
    if (decision.allowed()) {
      chain.doFilter(request, response);
      return;
    }
    SecurityEventLogger.rateLimited(ip, LIMIT_NAME, request);
    ErrorCode code = ErrorCode.TOO_MANY_REQUESTS;
    response.setStatus(code.getStatus().value());
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    jsonMapper.writeValue(response.getWriter(), ErrorResponse.of(code));
  }
}
