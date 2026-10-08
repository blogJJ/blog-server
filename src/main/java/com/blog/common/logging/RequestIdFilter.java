package com.blog.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 8자리 요청 ID를 만들어 로그 모든 줄과 응답 헤더 {@code X-Request-Id}에 싣는다 (T137, OPS-03).
 *
 * <p>500 오류 응답의 오류 번호도 이 값이라(OPS-02, GlobalExceptionHandler), 사용자가 알려 준 번호로 로그를 찾을 수 있다. 보안 필터보다 먼저
 * 돌아서 401·403 로그에도 붙는다. 클라이언트가 보낸 값은 로그 위조를 막으려고 쓰지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String MDC_KEY = "requestId";
  public static final String HEADER = "X-Request-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = UUID.randomUUID().toString().substring(0, 8);
    MDC.put(MDC_KEY, requestId);
    response.setHeader(HEADER, requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
