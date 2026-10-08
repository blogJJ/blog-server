package com.blog.auth.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내 정보 수정 (T098, USR-07, D-21, D-32). 바꿀 수 있는 것은 닉네임·전화번호·소개·프로필 사진·비밀번호뿐이고, 이메일과 이름은 못 바꾼다. */
@Service
public class MeService {

  public static final int BIO_MAX = 200;

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public MeService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  public record ProfileForm(String nickname, String phone, String bio) {}

  @Transactional
  public User updateProfile(Long userId, ProfileForm form) {
    User user = load(userId);
    String nickname = SignupService.validateNickname(form.nickname());
    String phone = SignupService.normalizePhone(form.phone());
    String bio = form.bio() == null ? null : form.bio().strip();
    if (bio != null && bio.codePointCount(0, bio.length()) > BIO_MAX) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "소개는 200자까지 쓸 수 있어요.");
    }
    if (!nickname.equals(user.getNickname()) && userRepository.existsByNickname(nickname)) {
      throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
    }
    user.changeProfile(nickname, phone, bio == null || bio.isEmpty() ? null : bio);
    try {
      userRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
    }
    return user;
  }

  /**
   * 현재 비밀번호를 확인하고 바꾼다. 로그인을 모두 끝내는 일은 부른 쪽이 새 로그인을 만들며 함께 한다.
   *
   * @throws BusinessException 현재 비밀번호가 틀리거나, 새 비밀번호가 규칙에 맞지 않거나 지금과 같으면 INVALID_INPUT
   */
  @Transactional
  public User changePassword(Long userId, String currentPassword, String newPassword) {
    User user = load(userId);
    if (currentPassword == null
        || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "현재 비밀번호가 맞지 않아요.");
    }
    SignupService.validatePassword(newPassword);
    if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "지금과 다른 비밀번호를 입력해 주세요.");
    }
    user.changePassword(passwordEncoder.encode(newPassword));
    return user;
  }

  /**
   * @return 이전 사진의 저장 이름 (부른 쪽이 파일을 지운다)
   */
  @Transactional
  public String changeProfileImage(Long userId, String storedName) {
    return load(userId).changeProfileImage(storedName);
  }

  private User load(Long userId) {
    return userRepository
        .findById(userId)
        .filter(User::isActive)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
  }
}
