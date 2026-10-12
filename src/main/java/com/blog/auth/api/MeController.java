package com.blog.auth.api;

import com.blog.auth.domain.RefreshToken;
import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
import com.blog.auth.service.LoginSessionService;
import com.blog.auth.service.MeService;
import com.blog.auth.service.MeService.ProfileForm;
import com.blog.auth.service.WithdrawService;
import com.blog.auth.service.WithdrawService.OwnedBlog;
import com.blog.auth.service.WithdrawService.WithdrawBlockedException;
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
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 내 정보 (T098, USR-07)와 회원 탈퇴 (T092, USR-05). 본인에게는 이메일·이름·전화번호를 가리지 않고 보여 준다. 바꿀 수 있는 것은
 * 닉네임·전화번호·소개·프로필 사진·비밀번호뿐이다.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

  private final UserRepository userRepository;
  private final MeService meService;
  private final ImageService imageService;
  private final LoginSessionService sessionService;
  private final AuthCookies authCookies;
  private final RateLimitGuard rateLimitGuard;
  private final WithdrawService withdrawService;

  public MeController(
      UserRepository userRepository,
      MeService meService,
      ImageService imageService,
      LoginSessionService sessionService,
      AuthCookies authCookies,
      RateLimitGuard rateLimitGuard,
      WithdrawService withdrawService) {
    this.userRepository = userRepository;
    this.meService = meService;
    this.imageService = imageService;
    this.sessionService = sessionService;
    this.authCookies = authCookies;
    this.rateLimitGuard = rateLimitGuard;
    this.withdrawService = withdrawService;
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

  public record WithdrawForm(String password) {}

  /**
   * 탈퇴할 수 있는지. blogs가 비어 있지 않으면 그 블로그를 위임하거나 폐쇄해야 한다.
   *
   * @param guide 화면에 보일 안내
   */
  public record WithdrawCheck(boolean allowed, List<OwnedBlog> blogs, String guide) {}

  /** 탈퇴를 막았을 때 본문: 다른 오류처럼 {code, message}에 블로그 목록과 안내를 더한다. */
  public record WithdrawBlockedResponse(
      String code, String message, List<OwnedBlog> blogs, String guide) {}

  static final String WITHDRAW_GUIDE =
      "블로그 관리 화면의 [위임·폐쇄] 탭에서 다른 멤버에게 블로그장을 넘기거나 블로그를 폐쇄해 주세요."
          + " 폐쇄 예정인 블로그는 폐쇄가 끝난 다음 날부터 탈퇴할 수 있어요.";

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

  /** 탈퇴 화면에서 먼저 확인한다 (T094) */
  @GetMapping("/withdraw")
  public WithdrawCheck withdrawCheck(@AuthenticationPrincipal AuthUser authUser) {
    List<OwnedBlog> blogs = withdrawService.blockingBlogs(authUser.id());
    return new WithdrawCheck(blogs.isEmpty(), blogs, blogs.isEmpty() ? null : WITHDRAW_GUIDE);
  }

  /** 회원 탈퇴 (T092, USR-05). 비밀번호를 다시 확인하고, 통과하면 로그인 쿠키도 지운다 */
  @PostMapping("/withdraw")
  public ResponseEntity<Void> withdraw(
      @AuthenticationPrincipal AuthUser authUser,
      @RequestBody WithdrawForm form,
      HttpServletRequest request,
      HttpServletResponse response) {
    rateLimitGuard.check("me-withdraw", request, 10, Duration.ofMinutes(10));
    withdrawService.withdraw(authUser.id(), form.password());
    authCookies.clear(response);
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(WithdrawBlockedException.class)
  public ResponseEntity<WithdrawBlockedResponse> withdrawBlocked(WithdrawBlockedException e) {
    ErrorCode code = e.getErrorCode();
    return ResponseEntity.status(code.getStatus())
        .body(
            new WithdrawBlockedResponse(code.name(), e.getMessage(), e.getBlogs(), WITHDRAW_GUIDE));
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
