package com.blog.auth.service;

import com.blog.auth.domain.SocialAccount;
import com.blog.auth.domain.SocialProvider;
import com.blog.auth.domain.User;
import com.blog.auth.repository.SocialAccountRepository;
import com.blog.auth.repository.UserRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구글·깃허브 소셜 로그인과 연결 (T157, D-128).
 *
 * <ul>
 *   <li>이미 연결된 제공자 계정이면 그 회원으로 로그인한다.
 *   <li>연결이 없으면 제공자가 <b>인증한</b> 이메일로 가입된 회원(탈퇴 제외)을 찾아 연결하고 로그인한다. 인증 안 된 이메일로는 연결하지 않는다.
 *   <li>그런 회원도 없으면 바로 만들지 않고 가입 마무리 화면(닉네임·약관 동의)으로 보낸다. 그동안의 정보는 서버 세션에 10분 둔다.
 *   <li>로그인 잠금(5번 실패, 5분)이면 막고, 계정 정지 중이면 로그인은 된다(읽기만, ADM-08). 일반 로그인과 같다 (LoginService).
 *   <li>비밀번호가 없는 회원은 마지막 연결을 끊을 수 없다.
 * </ul>
 */
@Service
public class SocialLoginService {

  /** 가입 마무리 화면에서 머물 수 있는 시간 */
  public static final Duration PENDING_TTL = Duration.ofMinutes(10);

  /** 소셜 로그인이 안 된 까닭. code는 화면 주소의 social_error 값 */
  public enum Refusal {
    /** 제공자가 이메일을 인증하지 않았다(또는 알려 주지 않았다) */
    EMAIL_UNVERIFIED("email_unverified"),
    /** 로그인 5번 실패로 잠김 */
    LOCKED("locked"),
    /** 그 제공자 계정이 이미 다른 회원에게 연결돼 있다 */
    ALREADY_LINKED("already_linked"),
    /** 이 회원은 같은 제공자의 다른 계정이 이미 연결돼 있다 */
    PROVIDER_TAKEN("provider_taken");

    private final String code;

    Refusal(String code) {
      this.code = code;
    }

    public String getCode() {
      return code;
    }
  }

  /** 소셜 로그인 결과 */
  public sealed interface Outcome {}

  /** 로그인할 회원. 부른 쪽이 LoginSessionService로 로그인 쿠키를 만든다 */
  public record LoggedIn(User user) implements Outcome {}

  /** 가입 마무리 화면으로 */
  public record NeedsSignup(PendingSocialSignup pending) implements Outcome {}

  public record Refused(Refusal refusal) implements Outcome {}

  /** 내 정보 화면의 연결 한 줄 */
  public record LinkedAccount(
      SocialProvider provider, String emailAtLink, LocalDateTime linkedAt) {}

  /**
   * 가입 마무리 화면에서 받는 값. 이메일·제공자 정보는 세션에 있는 것만 쓴다.
   *
   * @param name 제공자 이름을 미리 채워 두고, 고칠 수 있다. 가입 뒤에는 못 바꾼다 (D-32)
   */
  public record SignupForm(
      String name, String nickname, Boolean agreeTerms, Boolean agreePrivacy) {}

  private final SocialAccountRepository socialAccountRepository;
  private final UserRepository userRepository;
  private final Clock clock;

  public SocialLoginService(
      SocialAccountRepository socialAccountRepository, UserRepository userRepository, Clock clock) {
    this.socialAccountRepository = socialAccountRepository;
    this.userRepository = userRepository;
    this.clock = clock;
  }

  /**
   * 로그인 화면에서 소셜 로그인을 마쳤을 때.
   *
   * @param rememberMe 가입 마무리로 넘어가면 그때 쓰도록 담아 둔다
   */
  @Transactional
  public Outcome login(SocialProfile profile, boolean rememberMe) {
    Optional<SocialAccount> linked = findLink(profile);
    if (linked.isPresent()) {
      return loginAs(linked.get().getUser());
    }
    if (profile.verifiedEmail() == null) {
      return new Refused(Refusal.EMAIL_UNVERIFIED);
    }
    Optional<User> sameEmail =
        userRepository.findByEmail(profile.verifiedEmail()).filter(User::isActive);
    if (sameEmail.isEmpty()) {
      return new NeedsSignup(
          new PendingSocialSignup(
              profile.provider(),
              profile.providerUserId(),
              profile.verifiedEmail(),
              profile.name(),
              rememberMe,
              clock.instant().plus(PENDING_TTL)));
    }
    User user = sameEmail.get();
    if (socialAccountRepository
        .findByUserIdAndProvider(user.getId(), profile.provider())
        .isPresent()) {
      return new Refused(Refusal.PROVIDER_TAKEN);
    }
    if (user.isLockedAt(LocalDateTime.now(clock))) {
      return new Refused(Refusal.LOCKED);
    }
    saveLink(user, profile);
    return loginAs(user);
  }

  /** 내 정보 화면에서 로그인한 채로 소셜 계정을 연결할 때. 이메일이 달라도 된다(본인이 로그인해 있으므로). */
  @Transactional
  public Outcome link(Long userId, SocialProfile profile) {
    User user = activeUser(userId);
    Optional<SocialAccount> linked = findLink(profile);
    if (linked.isPresent()) {
      return linked.get().getUser().getId().equals(userId)
          ? new LoggedIn(user)
          : new Refused(Refusal.ALREADY_LINKED);
    }
    if (socialAccountRepository.findByUserIdAndProvider(userId, profile.provider()).isPresent()) {
      return new Refused(Refusal.PROVIDER_TAKEN);
    }
    saveLink(user, profile);
    return new LoggedIn(user);
  }

  /**
   * 가입 마무리: 일반 가입과 같은 규칙으로 이름·닉네임·약관 동의를 검사하고, 비밀번호·전화번호 없이 회원을 만든 뒤 소셜 계정을 연결한다.
   *
   * @throws BusinessException 세션 정보가 지났으면 SOCIAL_SIGNUP_EXPIRED, 닉네임이 겹치면 DUPLICATE_NICKNAME
   */
  @Transactional
  public User completeSignup(PendingSocialSignup pending, SignupForm form) {
    if (pending == null || pending.isExpiredAt(clock.instant())) {
      throw new BusinessException(ErrorCode.SOCIAL_SIGNUP_EXPIRED);
    }
    String name = SignupService.validateName(form.name());
    String nickname = SignupService.validateNickname(form.nickname());
    if (!Boolean.TRUE.equals(form.agreeTerms()) || !Boolean.TRUE.equals(form.agreePrivacy())) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "이용약관과 개인정보 수집·이용에 모두 동의해 주세요.");
    }
    if (userRepository.existsByEmail(pending.verifiedEmail())) {
      throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
    }
    if (userRepository.existsByNickname(nickname)) {
      throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
    }
    if (socialAccountRepository
        .findWithUser(pending.provider(), pending.providerUserId())
        .isPresent()) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 소셜 계정이에요. 다시 로그인해 주세요.");
    }
    User user =
        new User(pending.verifiedEmail(), null, name, nickname, null, LocalDateTime.now(clock));
    try {
      userRepository.saveAndFlush(user);
      socialAccountRepository.saveAndFlush(
          new SocialAccount(
              user, pending.provider(), pending.providerUserId(), pending.verifiedEmail()));
    } catch (DataIntegrityViolationException e) {
      // 같은 이메일·닉네임·소셜 계정으로 동시에 가입한 경우
      throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 이메일이거나 쓰고 있는 닉네임이에요.");
    }
    return user;
  }

  /**
   * 연결을 끊는다.
   *
   * @throws BusinessException 연결이 없으면 NOT_FOUND, 비밀번호가 없고 마지막 연결이면 SOCIAL_LAST_LOGIN_METHOD
   */
  @Transactional
  public void unlink(Long userId, SocialProvider provider) {
    User user =
        userRepository
            .findByIdForUpdate(userId)
            .filter(User::isActive)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    SocialAccount account =
        socialAccountRepository
            .findByUserIdAndProvider(userId, provider)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "연결된 계정이 없어요."));
    if (!user.hasPassword() && socialAccountRepository.countByUserId(userId) <= 1) {
      throw new BusinessException(ErrorCode.SOCIAL_LAST_LOGIN_METHOD);
    }
    socialAccountRepository.delete(account);
  }

  @Transactional(readOnly = true)
  public List<LinkedAccount> linkedAccounts(Long userId) {
    return socialAccountRepository.findByUserIdOrderByIdAsc(userId).stream()
        .map(s -> new LinkedAccount(s.getProvider(), s.getEmailAtLink(), s.getCreatedAt()))
        .toList();
  }

  /** 연결을 찾는다. 탈퇴한 회원의 연결이 남아 있으면(탈퇴 전 버전에서 탈퇴한 경우) 지우고 없는 것으로 본다. */
  private Optional<SocialAccount> findLink(SocialProfile profile) {
    Optional<SocialAccount> linked =
        socialAccountRepository.findWithUser(profile.provider(), profile.providerUserId());
    if (linked.isPresent() && !linked.get().getUser().isActive()) {
      socialAccountRepository.delete(linked.get());
      socialAccountRepository.flush();
      return Optional.empty();
    }
    return linked;
  }

  private Outcome loginAs(User user) {
    if (user.isLockedAt(LocalDateTime.now(clock))) {
      return new Refused(Refusal.LOCKED);
    }
    user.resetLoginFailures();
    return new LoggedIn(user);
  }

  private void saveLink(User user, SocialProfile profile) {
    try {
      socialAccountRepository.saveAndFlush(
          new SocialAccount(
              user, profile.provider(), profile.providerUserId(), profile.verifiedEmail()));
    } catch (DataIntegrityViolationException e) {
      // 같은 소셜 계정으로 동시에 연결한 경우
      throw new BusinessException(ErrorCode.CONFLICT);
    }
  }

  private User activeUser(Long userId) {
    return userRepository
        .findById(userId)
        .filter(User::isActive)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
  }
}
