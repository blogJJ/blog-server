package com.blog.auth.service;

import com.blog.auth.domain.User;
import com.blog.auth.domain.VerificationPurpose;
import com.blog.auth.repository.UserRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.mail.MailService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 찾기·재설정 (T096, USR-06, SEC-05, D-25, SC-003).
 *
 * <ul>
 *   <li>이메일을 입력하거나, 이메일 찾기 결과의 임시 토큰으로 6자리 번호를 요청한다. 가입 여부와 관계없이 응답과 1분 재발송 제한이 같다.
 *   <li>번호(30분, 1회용, 5번 틀리면 무효)와 새 비밀번호를 같은 화면에서 보낸다.
 *   <li>바뀌면 그 회원의 로그인을 모두 끝내고 로그인 잠금을 푼다.
 * </ul>
 */
@Service
public class PasswordResetService {

  private final UserRepository userRepository;
  private final VerificationCodeService codeService;
  private final FindEmailService findEmailService;
  private final LoginSessionService sessionService;
  private final MailService mailService;
  private final PasswordEncoder passwordEncoder;

  public PasswordResetService(
      UserRepository userRepository,
      VerificationCodeService codeService,
      FindEmailService findEmailService,
      LoginSessionService sessionService,
      MailService mailService,
      PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.codeService = codeService;
    this.findEmailService = findEmailService;
    this.sessionService = sessionService;
    this.mailService = mailService;
    this.passwordEncoder = passwordEncoder;
  }

  /**
   * 인증번호를 보낸다. 가입된 이메일이 아니어도 번호는 만들어(메일은 안 보냄) 응답과 1분 제한을 똑같이 둔다.
   *
   * @param email 비밀번호 찾기 화면에서 입력한 이메일. findToken이 있으면 쓰지 않는다
   * @param findToken 이메일 찾기 결과의 임시 토큰
   */
  @Transactional
  public void sendCode(String email, String findToken) {
    String target = target(email, findToken, true);
    String code = codeService.issue(target, VerificationPurpose.PASSWORD_RESET);
    userRepository
        .findByEmail(target)
        .filter(User::isActive)
        .ifPresent(
            u ->
                mailService.send(
                    target,
                    "[블로그] 비밀번호 재설정 인증번호",
                    "인증번호: "
                        + code
                        + "\n\n30분 안에 비밀번호 찾기 화면에 입력해 주세요.\n"
                        + "본인이 요청하지 않았다면 이 메일은 무시해 주세요. 비밀번호는 바뀌지 않아요."));
  }

  /**
   * 번호를 확인하고 비밀번호를 바꾼다. 새 비밀번호를 먼저 검사해서, 비밀번호 규칙 때문에 번호 기회가 줄지 않게 한다.
   *
   * @throws BusinessException 번호가 틀리면 CODE_INVALID(남은 횟수), 만료·5번 틀림·사용함이면 CODE_EXPIRED
   */
  @Transactional(noRollbackFor = BusinessException.class)
  public void reset(String email, String findToken, String code, String newPassword) {
    SignupService.validatePassword(newPassword);
    String target = target(email, findToken, false);
    codeService.verifyAndUse(target, VerificationPurpose.PASSWORD_RESET, code);
    User user =
        userRepository
            .findByEmail(target)
            .filter(User::isActive)
            .orElseThrow(() -> new BusinessException(ErrorCode.CODE_EXPIRED));
    user.changePassword(passwordEncoder.encode(newPassword));
    sessionService.revokeAll(user.getId());
  }

  private String target(String email, String findToken, boolean requestingCode) {
    if (findToken != null && !findToken.isBlank()) {
      return requestingCode
          ? findEmailService.activeOwner(findToken).getEmail()
          : findEmailService.owner(findToken).getEmail();
    }
    return SignupService.normalizeEmail(email);
  }
}
