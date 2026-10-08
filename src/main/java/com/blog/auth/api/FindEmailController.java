package com.blog.auth.api;

import com.blog.auth.service.FindEmailService;
import com.blog.auth.service.FindEmailService.Found;
import com.blog.common.ratelimit.RateLimitGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 이메일 찾기 (T097, USR-08). 같은 IP는 10분에 5번까지 */
@RestController
public class FindEmailController {

  private final FindEmailService findEmailService;
  private final RateLimitGuard rateLimitGuard;

  public FindEmailController(FindEmailService findEmailService, RateLimitGuard rateLimitGuard) {
    this.findEmailService = findEmailService;
    this.rateLimitGuard = rateLimitGuard;
  }

  public record FindRequest(String name, String phone) {}

  /** 일치하는 계정이 없으면 빈 목록 */
  @PostMapping("/api/auth/find-email")
  public List<Found> find(@RequestBody FindRequest body, HttpServletRequest request) {
    rateLimitGuard.check("find-email", request, 5, Duration.ofMinutes(10));
    return findEmailService.find(body.name(), body.phone());
  }
}
