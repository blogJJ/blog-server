package com.blog.auth.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.logging.SecurityEventLogger;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 (T034, USR-03, SEC-03, SEC-13, D-80).
 *
 * <ul>
 *   <li>이메일이 없거나 탈퇴했거나 비밀번호가 틀리면 모두 같은 문구(LOGIN_FAILED). 가입 여부가 드러나지 않게 이메일이 없어도 bcrypt 비교를 한 번 한다.
 *   <li>계정별 연속 5번 실패면 5분 잠금. 3번 실패한 뒤부터는 Turnstile 사람 확인을 통과해야 비밀번호를 확인한다.
 *   <li>IP별 횟수 제한은 컨트롤러에서 RateLimiter로 건다.
 *   <li>계정 정지(ADM-08) 중이어도 로그인은 된다. 읽기만 할 수 있는 것은 AccountGuard가 막는다.
 * </ul>
 *
 * <p>실패 횟수는 예외를 던져도 저장되어야 하므로 BusinessException에 트랜잭션을 되돌리지 않는다.
 */
@Service
public class LoginService {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final TurnstileVerifier turnstileVerifier;
  private final Clock clock;
  private final String dummyHash;

  public LoginService(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      TurnstileVerifier turnstileVerifier,
      Clock clock) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.turnstileVerifier = turnstileVerifier;
    this.clock = clock;
    this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
  }

  /**
   * 이메일·비밀번호를 확인하고 회원을 돌려준다. 로그인 쿠키는 컨트롤러가 LoginSessionService로 만든다.
   *
   * @param turnstileToken 사람 확인 토큰. 3번 실패 전에는 없어도 된다
   * @param ip 로그와 Turnstile 확인용
   */
  @Transactional(noRollbackFor = BusinessException.class)
  public User authenticate(String rawEmail, String password, String turnstileToken, String ip) {
    LocalDateTime now = LocalDateTime.now(clock);
    Optional<User> found =
        Optional.ofNullable(User.normalizeEmail(rawEmail))
            .flatMap(userRepository::findByEmail)
            .filter(User::isActive);
    if (found.isEmpty()) {
      passwordEncoder.matches(password == null ? "" : password, dummyHash);
      SecurityEventLogger.loginFailed(rawEmail, ip);
      throw new BusinessException(ErrorCode.LOGIN_FAILED);
    }
    User user = found.get();
    if (user.isLockedAt(now)) {
      throw new BusinessException(
          ErrorCode.ACCOUNT_LOCKED,
          "로그인을 5번 잘못해서 잠겼어요. " + user.getLockedUntil().format(TIME) + " 이후에 다시 시도해 주세요.");
    }
    if (user.needsCaptcha() && !turnstileVerifier.verify(turnstileToken, ip)) {
      throw new BusinessException(ErrorCode.CAPTCHA_REQUIRED);
    }
    if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
      boolean locked = user.recordLoginFailure(now);
      SecurityEventLogger.loginFailed(rawEmail, ip);
      if (locked) {
        throw new BusinessException(
            ErrorCode.ACCOUNT_LOCKED,
            "로그인을 5번 잘못해서 잠겼어요. " + user.getLockedUntil().format(TIME) + " 이후에 다시 시도해 주세요.");
      }
      if (user.needsCaptcha()) {
        throw new BusinessException(
            ErrorCode.CAPTCHA_REQUIRED, "이메일 또는 비밀번호가 맞지 않아요. 다음에는 사람 확인을 해 주세요.");
      }
      throw new BusinessException(ErrorCode.LOGIN_FAILED);
    }
    user.resetLoginFailures();
    return user;
  }
}
