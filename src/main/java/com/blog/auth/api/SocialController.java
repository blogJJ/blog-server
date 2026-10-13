package com.blog.auth.api;

import com.blog.auth.domain.SocialProvider;
import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.auth.service.PendingSocialSignup;
import com.blog.auth.service.SocialClientRegistrations;
import com.blog.auth.service.SocialLoginService;
import com.blog.auth.service.SocialLoginService.LinkedAccount;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.ratelimit.RateLimitGuard;
import com.blog.common.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 소셜 로그인 가입 마무리와 내 정보 화면의 연결·해제 (T157, D-128). 구글·깃허브로 보내고 돌아오는 주소는 Spring Security가 맡는다({@code
 * /oauth2/authorization/*}, {@code /login/oauth2/code/*}, SocialLoginHandlers).
 */
@RestController
public class SocialController {

  private final SocialLoginService socialLoginService;
  private final SocialClientRegistrations registrations;
  private final SocialLoginHandlers handlers;
  private final UserRepository userRepository;
  private final RateLimitGuard rateLimitGuard;
  private final Clock clock;

  public SocialController(
      SocialLoginService socialLoginService,
      SocialClientRegistrations registrations,
      SocialLoginHandlers handlers,
      UserRepository userRepository,
      RateLimitGuard rateLimitGuard,
      Clock clock) {
    this.socialLoginService = socialLoginService;
    this.registrations = registrations;
    this.handlers = handlers;
    this.userRepository = userRepository;
    this.rateLimitGuard = rateLimitGuard;
    this.clock = clock;
  }

  /** 가입 마무리 화면에 보여 줄 값. 제공자 회원 번호는 내보내지 않는다 */
  public record PendingResponse(SocialProvider provider, String label, String email, String name) {}

  public record SignupResponse(Long id, String nickname) {}

  /**
   * 내 정보 화면의 소셜 로그인 한 줄.
   *
   * @param enabled 서버에 키가 있어 새로 연결할 수 있는지
   * @param email 연결할 때 제공자가 확인해 준 이메일. 연결 안 했거나 없으면 null
   */
  public record SocialAccountRow(
      SocialProvider provider,
      String label,
      boolean enabled,
      boolean linked,
      String email,
      LocalDateTime linkedAt) {}

  /**
   * @param hasPassword 비밀번호가 있는지. 없으면 마지막 연결은 끊을 수 없다
   */
  public record SocialAccountsResponse(boolean hasPassword, List<SocialAccountRow> accounts) {}

  @GetMapping("/api/auth/social/pending")
  public PendingResponse pending(HttpServletRequest request) {
    PendingSocialSignup pending =
        SocialSession.findPending(request, clock.instant())
            .orElseThrow(() -> new BusinessException(ErrorCode.SOCIAL_SIGNUP_EXPIRED));
    return new PendingResponse(
        pending.provider(), pending.provider().getLabel(), pending.verifiedEmail(), pending.name());
  }

  /** 가입을 끝내고 바로 로그인한다. 로그인 화면에서 "로그인 유지"를 체크하고 왔으면 그대로 */
  @PostMapping("/api/auth/social/signup")
  @ResponseStatus(HttpStatus.CREATED)
  public SignupResponse signup(
      @RequestBody SocialLoginService.SignupForm form,
      HttpServletRequest request,
      HttpServletResponse response) {
    rateLimitGuard.check("signup", request, 10, Duration.ofMinutes(10));
    PendingSocialSignup pending =
        SocialSession.findPending(request, clock.instant())
            .orElseThrow(() -> new BusinessException(ErrorCode.SOCIAL_SIGNUP_EXPIRED));
    User user = socialLoginService.completeSignup(pending, form);
    SocialSession.clearPending(request);
    handlers.startSession(request, response, user, pending.rememberMe());
    return new SignupResponse(user.getId(), user.getNickname());
  }

  @GetMapping("/api/me/social")
  public SocialAccountsResponse accounts(@AuthenticationPrincipal AuthUser authUser) {
    User user =
        userRepository
            .findById(authUser.id())
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    List<LinkedAccount> linked = socialLoginService.linkedAccounts(authUser.id());
    List<SocialAccountRow> rows = new ArrayList<>();
    for (SocialProvider provider : SocialProvider.values()) {
      LinkedAccount account =
          linked.stream().filter(a -> a.provider() == provider).findFirst().orElse(null);
      boolean enabled = registrations.isEnabled(provider);
      if (!enabled && account == null) {
        continue; // 키가 없고 연결도 없는 제공자는 보이지 않는다
      }
      rows.add(
          new SocialAccountRow(
              provider,
              provider.getLabel(),
              enabled,
              account != null,
              account == null ? null : account.emailAtLink(),
              account == null ? null : account.linkedAt()));
    }
    return new SocialAccountsResponse(user.hasPassword(), rows);
  }

  /**
   * 연결 시작: 세션에 회원 번호를 적고 구글·깃허브로 갈 주소를 돌려준다. 화면이 그 주소로 이동한다. 쓰기 요청이라 CSRF 토큰을 확인한다(다른 사이트가 몰래 연결을
   * 시작하지 못하게).
   */
  @PostMapping("/api/me/social/{provider}/link")
  public Map<String, String> startLink(
      @AuthenticationPrincipal AuthUser authUser,
      @PathVariable String provider,
      HttpServletRequest request) {
    rateLimitGuard.check("social-link", request, 10, Duration.ofMinutes(1));
    SocialProvider p = enabledProvider(provider);
    SocialSession.startLink(request, authUser.id());
    return Map.of("url", "/oauth2/authorization/" + p.getId() + "?link=1");
  }

  @DeleteMapping("/api/me/social/{provider}")
  public ResponseEntity<Void> unlink(
      @AuthenticationPrincipal AuthUser authUser, @PathVariable String provider) {
    SocialProvider p =
        SocialProvider.fromId(provider)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    socialLoginService.unlink(authUser.id(), p);
    return ResponseEntity.noContent().build();
  }

  private SocialProvider enabledProvider(String id) {
    return SocialProvider.fromId(id)
        .filter(registrations::isEnabled)
        .orElseThrow(() -> new BusinessException(ErrorCode.SOCIAL_PROVIDER_DISABLED));
  }
}
