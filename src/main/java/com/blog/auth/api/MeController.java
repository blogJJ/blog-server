package com.blog.auth.api;

import com.blog.auth.domain.RefreshToken;
import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
import com.blog.auth.service.LoginSessionService;
import com.blog.auth.service.MeService;
import com.blog.auth.service.MeService.ProfileForm;
import com.blog.board.service.ImageService;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.ratelimit.RateLimitGuard;
import com.blog.common.security.AuthCookies;
import com.blog.common.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 내 정보 (T098, USR-07). 본인에게는 이메일·이름·전화번호를 가리지 않고 보여 준다. 바꿀 수 있는 것은 닉네임·전화번호·소개·프로필 사진·비밀번호뿐이다. */
@RestController
@RequestMapping("/api/me")
public class MeController {

  private final UserRepository userRepository;
  private final MeService meService;
  private final ImageService imageService;
  private final LoginSessionService sessionService;
  private final AuthCookies authCookies;
  private final RateLimitGuard rateLimitGuard;

  public MeController(
      UserRepository userRepository,
      MeService meService,
      ImageService imageService,
      LoginSessionService sessionService,
      AuthCookies authCookies,
      RateLimitGuard rateLimitGuard) {
    this.userRepository = userRepository;
    this.meService = meService;
    this.imageService = imageService;
    this.sessionService = sessionService;
    this.authCookies = authCookies;
    this.rateLimitGuard = rateLimitGuard;
  }

  /**
   * @param suspendedUntil 계정 정지 중이면 끝나는 시각, 아니면 null (ADM-08)
   */
  public record MeResponse(
      Long id,
      String email,
      String name,
      String nickname,
      String phone,
      String bio,
      String profileImage,
      UserRole role,
      LocalDateTime suspendedUntil,
      LocalDateTime createdAt) {

    static MeResponse of(User user) {
      return new MeResponse(
          user.getId(),
          user.getEmail(),
          user.getName(),
          user.getNickname(),
          user.getPhone(),
          user.getBio(),
          ImageService.url(user.getProfileImage()),
          user.getRole(),
          user.getSuspendedUntil(),
          user.getCreatedAt());
    }
  }

  public record PasswordForm(String currentPassword, String newPassword) {}

  @GetMapping
  public MeResponse me(@AuthenticationPrincipal AuthUser authUser) {
    return MeResponse.of(
        userRepository
            .findById(authUser.id())
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED)));
  }

  /** 닉네임·전화번호·소개 */
  @PutMapping
  public MeResponse update(
      @AuthenticationPrincipal AuthUser authUser, @RequestBody ProfileForm form) {
    return MeResponse.of(meService.updateProfile(authUser.id(), form));
  }

  /** 비밀번호 변경. 이 회원의 로그인을 모두 끝내고(SEC-04), 지금 기기에는 같은 "로그인 유지" 설정으로 새 로그인을 만들어 바로 이어 쓰게 한다. */
  @PutMapping("/password")
  public ResponseEntity<Void> changePassword(
      @AuthenticationPrincipal AuthUser authUser,
      @RequestBody PasswordForm form,
      HttpServletRequest request,
      HttpServletResponse response) {
    rateLimitGuard.check("me-password", request, 10, Duration.ofMinutes(10));
    User user = meService.changePassword(authUser.id(), form.currentPassword(), form.newPassword());
    boolean rememberMe =
        authUser.sessionId() != null
            && sessionService
                .findActive(authUser.sessionId())
                .map(RefreshToken::isRememberMe)
                .orElse(false);
    sessionService.revokeAll(user.getId());
    LoginSessionService.Issued issued =
        sessionService.start(user, rememberMe, request.getHeader(HttpHeaders.USER_AGENT));
    authCookies.write(response, issued.accessToken(), issued.refreshToken(), issued.cookieMaxAge());
    return ResponseEntity.noContent().build();
  }

  /** 프로필 사진 (3MB 1장). 이전 사진은 지운다 */
  @PutMapping("/profile-image")
  public Map<String, String> changeProfileImage(
      @AuthenticationPrincipal AuthUser authUser, @RequestParam("file") MultipartFile file) {
    String stored = imageService.storeSingle(file);
    String old;
    try {
      old = meService.changeProfileImage(authUser.id(), stored);
    } catch (RuntimeException e) {
      imageService.deleteQuietly(stored);
      throw e;
    }
    imageService.deleteQuietly(old);
    return Map.of("profileImage", ImageService.url(stored));
  }

  @DeleteMapping("/profile-image")
  public ResponseEntity<Void> deleteProfileImage(@AuthenticationPrincipal AuthUser authUser) {
    imageService.deleteQuietly(meService.changeProfileImage(authUser.id(), null));
    return ResponseEntity.noContent().build();
  }
}
