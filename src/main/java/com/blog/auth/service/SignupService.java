package com.blog.auth.service;

import com.blog.auth.domain.User;
import com.blog.auth.domain.VerificationPurpose;
import com.blog.auth.repository.UserRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.mail.MailService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입 (T031, T032, USR-01, USR-02, SEC-01, SEC-02). 이메일 인증 → 비밀번호 → 이름·닉네임·전화번호·약관 동의 순서이고, 서버는 마지막
 * 단계에서 모든 값을 다시 검사한다.
 */
@Service
public class SignupService {

  /** 8~15자, 영문·숫자·특수문자를 모두 포함. 공백 불가. 대소문자 구분 (SEC-02) */
  static final Pattern PASSWORD =
      Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d\\s])\\S{8,15}$");

  /** 2~12자 한글·영문·숫자 */
  static final Pattern NICKNAME = Pattern.compile("^[가-힣A-Za-z0-9]{2,12}$");

  /** 이 말이 들어간 닉네임은 쓸 수 없다. 대소문자 무시 (USR-01) */
  static final List<String> RESERVED_NICKNAME_WORDS = List.of("관리자", "admin", "운영자", "탈퇴한회원");

  static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  /** 휴대폰 번호: 숫자만 10~11자리, 01로 시작 */
  static final Pattern PHONE = Pattern.compile("^01\\d{8,9}$");

  private final UserRepository userRepository;
  private final VerificationCodeService codeService;
  private final MailService mailService;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  public SignupService(
      UserRepository userRepository,
      VerificationCodeService codeService,
      MailService mailService,
      PasswordEncoder passwordEncoder,
      Clock clock) {
    this.userRepository = userRepository;
    this.codeService = codeService;
    this.mailService = mailService;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
  }

  /**
   * 1단계: 인증번호 메일을 보낸다. 이미 가입된 이메일이어도 화면 응답과 1분 제한은 똑같고, 메일로만 "이미 가입된 이메일"이라고 알린다 (D-28, SC-003).
   * 메일이 실패하면 번호가 저장되지 않는다 (OPS-07).
   */
  @Transactional
  public void sendSignupCode(String rawEmail) {
    String email = normalizeEmail(rawEmail);
    String code = codeService.issue(email, VerificationPurpose.SIGNUP);
    if (userRepository.existsByEmail(email)) {
      mailService.send(
          email,
          "[블로그] 이미 가입된 이메일이에요",
          "이 이메일로 가입된 계정이 있어요.\n"
              + "로그인하거나, 비밀번호가 기억나지 않으면 비밀번호 찾기를 이용해 주세요.\n\n"
              + "본인이 요청하지 않았다면 이 메일은 무시해 주세요.");
      return;
    }
    mailService.send(
        email,
        "[블로그] 회원가입 인증번호",
        "인증번호: " + code + "\n\n10분 안에 가입 화면에 입력해 주세요.\n본인이 요청하지 않았다면 이 메일은 무시해 주세요.");
  }

  /** 1단계: 인증번호 확인 */
  public void verifySignupCode(String rawEmail, String code) {
    codeService.verify(normalizeEmail(rawEmail), VerificationPurpose.SIGNUP, code);
  }

  /** 가입에 필요한 값. 비밀번호 확인(두 번 입력)은 화면에서 맞춘다. */
  public record SignupForm(
      String email,
      String password,
      String name,
      String nickname,
      String phone,
      Boolean agreeTerms,
      Boolean agreePrivacy) {}

  /** 3단계: 가입을 끝낸다. 인증 완료를 다시 확인하고 모든 값을 검사한다. */
  @Transactional
  public User signup(SignupForm form) {
    String email = normalizeEmail(form.email());
    validatePassword(form.password());
    String name = form.name() == null ? "" : form.name().strip();
    if (name.isEmpty() || name.length() > 30) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "이름을 1~30자로 입력해 주세요.");
    }
    String nickname = validateNickname(form.nickname());
    String phone = normalizePhone(form.phone());
    if (!Boolean.TRUE.equals(form.agreeTerms()) || !Boolean.TRUE.equals(form.agreePrivacy())) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "이용약관과 개인정보 수집·이용에 모두 동의해 주세요.");
    }
    if (userRepository.existsByEmail(email)) {
      throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
    }
    if (userRepository.existsByNickname(nickname)) {
      throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
    }
    codeService.consumeVerified(email, VerificationPurpose.SIGNUP);
    User user =
        new User(
            email,
            passwordEncoder.encode(form.password()),
            name,
            nickname,
            phone,
            LocalDateTime.now(clock));
    try {
      return userRepository.saveAndFlush(user);
    } catch (DataIntegrityViolationException e) {
      // 같은 이메일·닉네임으로 동시에 가입한 경우
      throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 이메일이거나 쓰고 있는 닉네임이에요.");
    }
  }

  static String normalizeEmail(String rawEmail) {
    String email = User.normalizeEmail(rawEmail);
    if (email == null || email.length() > 100 || !EMAIL.matcher(email).matches()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "이메일 주소를 확인해 주세요.");
    }
    return email;
  }

  static void validatePassword(String password) {
    if (password == null || !PASSWORD.matcher(password).matches()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "비밀번호는 8~15자로 영문, 숫자, 특수문자를 모두 넣어 주세요.");
    }
  }

  static String validateNickname(String rawNickname) {
    String nickname = rawNickname == null ? "" : rawNickname.strip();
    if (!NICKNAME.matcher(nickname).matches()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "닉네임은 2~12자 한글, 영문, 숫자로 입력해 주세요.");
    }
    String lower = nickname.toLowerCase(Locale.ROOT);
    for (String word : RESERVED_NICKNAME_WORDS) {
      if (lower.contains(word)) {
        throw new BusinessException(ErrorCode.INVALID_INPUT, "쓸 수 없는 닉네임이에요.");
      }
    }
    return nickname;
  }

  /** 하이픈·공백을 빼고 숫자만 남긴다 */
  static String normalizePhone(String rawPhone) {
    String phone = rawPhone == null ? "" : rawPhone.replaceAll("[\\s-]", "");
    if (!PHONE.matcher(phone).matches()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "휴대폰 번호를 숫자로 입력해 주세요.");
    }
    return phone;
  }
}
