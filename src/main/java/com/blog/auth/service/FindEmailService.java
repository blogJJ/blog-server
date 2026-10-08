package com.blog.auth.service;

import com.blog.auth.domain.AccountFindToken;
import com.blog.auth.domain.User;
import com.blog.auth.domain.UserStatus;
import com.blog.auth.repository.AccountFindTokenRepository;
import com.blog.auth.repository.UserRepository;
import com.blog.common.crypto.HashUtil;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.privacy.Masking;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이메일 찾기 (T097, USR-08, D-23, D-26, D-27). 이름(앞뒤 공백 제거)과 전화번호(숫자만)가 같은 계정을 모두 찾아, 가린 이메일과 가입일을 보여
 * 준다. 탈퇴 계정은 뺀다. 계정마다 10분짜리 임시 토큰을 주고, 결과 화면의 [비밀번호 재설정]은 그 토큰으로 요청한다.
 */
@Service
public class FindEmailService {

  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * @param token 비밀번호 재설정 인증번호를 요청할 때 쓰는 임시 토큰 (10분)
   */
  public record Found(String email, LocalDateTime joinedAt, String token) {}

  private final UserRepository userRepository;
  private final AccountFindTokenRepository tokenRepository;
  private final HashUtil hashUtil;
  private final Clock clock;

  public FindEmailService(
      UserRepository userRepository,
      AccountFindTokenRepository tokenRepository,
      HashUtil hashUtil,
      Clock clock) {
    this.userRepository = userRepository;
    this.tokenRepository = tokenRepository;
    this.hashUtil = hashUtil;
    this.clock = clock;
  }

  @Transactional
  public List<Found> find(String rawName, String rawPhone) {
    String name = rawName == null ? "" : rawName.strip();
    if (name.isEmpty() || name.length() > 30) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "이름을 입력해 주세요.");
    }
    String phone = SignupService.normalizePhone(rawPhone);
    LocalDateTime now = LocalDateTime.now(clock);
    return userRepository
        .findByNameAndPhoneAndStatusOrderByIdAsc(name, phone, UserStatus.ACTIVE)
        .stream()
        .filter(u -> u.getEmail() != null)
        .map(u -> new Found(Masking.email(u.getEmail()), u.getCreatedAt(), issueToken(u, now)))
        .toList();
  }

  /**
   * 임시 토큰의 계정. 인증번호 요청은 토큰이 살아 있을 때만 받는다 (10분).
   *
   * @throws BusinessException 없거나 만료됐으면 INVALID_INPUT
   */
  @Transactional(readOnly = true)
  public User activeOwner(String rawToken) {
    return findOwner(rawToken, true);
  }

  /**
   * 재설정을 끝낼 때의 계정. 인증번호(30분, 1회용, 5번)가 실제 확인이라, 인증번호를 받은 뒤 토큰 10분이 지나도 받아 준다.
   *
   * @throws BusinessException 없으면 INVALID_INPUT
   */
  @Transactional(readOnly = true)
  public User owner(String rawToken) {
    return findOwner(rawToken, false);
  }

  private User findOwner(String rawToken, boolean mustBeActive) {
    LocalDateTime now = LocalDateTime.now(clock);
    return (rawToken == null || rawToken.isBlank()
            ? java.util.Optional.<AccountFindToken>empty()
            : tokenRepository.findByTokenHash(hashUtil.hash(rawToken)))
        .filter(t -> !mustBeActive || t.isActiveAt(now))
        .map(AccountFindToken::getUser)
        .filter(u -> u.isActive() && u.getEmail() != null)
        .orElseThrow(
            () ->
                new BusinessException(ErrorCode.INVALID_INPUT, "찾기 결과가 만료되었어요. 이메일 찾기를 다시 해 주세요."));
  }

  private String issueToken(User user, LocalDateTime now) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    tokenRepository.save(new AccountFindToken(user, hashUtil.hash(raw), now));
    return raw;
  }
}
