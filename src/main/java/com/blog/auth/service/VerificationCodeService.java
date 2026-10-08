package com.blog.auth.service;

import com.blog.auth.domain.VerificationCode;
import com.blog.auth.domain.VerificationPurpose;
import com.blog.auth.repository.VerificationCodeRepository;
import com.blog.common.crypto.HashUtil;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이메일 인증번호 (T030, USR-02, D-10, D-28, D-42).
 *
 * <ul>
 *   <li>SecureRandom 6자리. DB에는 HMAC 해시만 둔다(HashUtil).
 *   <li>가입 10분, 재설정 30분 만료. 5번 틀리면 무효.
 *   <li>재발송은 1분에 한 번이고, 보내면 이전 번호는 무효.
 *   <li>맞게 입력하면 verified_at을 남기고, 가입 마지막 단계에서 {@link #consumeVerified}로 다시 확인한 뒤 1회용으로 쓴다.
 * </ul>
 *
 * <p>메일 발송이 실패하면 부른 쪽 트랜잭션이 되돌아가 새 번호가 남지 않는다. 그래서 그 번호는 무효이고 1분 재발송 제한에도 세지 않는다 (OPS-07).
 */
@Service
public class VerificationCodeService {

  static final Duration RESEND_INTERVAL = Duration.ofMinutes(1);

  /** 인증을 마친 뒤 가입을 끝내야 하는 시간 */
  static final Duration VERIFIED_VALID_FOR = Duration.ofMinutes(30);

  private static final SecureRandom RANDOM = new SecureRandom();

  private final VerificationCodeRepository repository;
  private final HashUtil hashUtil;
  private final Clock clock;

  public VerificationCodeService(
      VerificationCodeRepository repository, HashUtil hashUtil, Clock clock) {
    this.repository = repository;
    this.hashUtil = hashUtil;
    this.clock = clock;
  }

  /**
   * 새 번호를 만들어 저장하고 원문을 돌려준다. 메일은 부른 쪽이 같은 트랜잭션 안에서 보낸다.
   *
   * @throws BusinessException 1분 안에 다시 요청하면 CODE_RESEND_TOO_SOON
   */
  @Transactional
  public String issue(String email, VerificationPurpose purpose) {
    LocalDateTime now = now();
    repository
        .findFirstByEmailAndPurposeOrderByIdDesc(email, purpose)
        .filter(last -> last.getCreatedAt().isAfter(now.minus(RESEND_INTERVAL)))
        .ifPresent(
            last -> {
              throw new BusinessException(ErrorCode.CODE_RESEND_TOO_SOON);
            });
    repository.expireActive(email, purpose, now);
    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
    repository.save(new VerificationCode(email, purpose, hashUtil.hash(code), now));
    return code;
  }

  /**
   * 입력한 번호를 확인한다. 틀리면 틀린 횟수를 저장하고(예외로 되돌리지 않음) 남은 횟수를 알려 준다.
   *
   * @throws BusinessException CODE_EXPIRED(없음·만료·5번 틀림·사용함) 또는 CODE_INVALID
   */
  @Transactional(noRollbackFor = BusinessException.class)
  public void verify(String email, VerificationPurpose purpose, String code) {
    LocalDateTime now = now();
    VerificationCode latest =
        repository
            .findFirstByEmailAndPurposeOrderByIdDesc(email, purpose)
            .filter(c -> c.isUsableAt(now))
            .orElseThrow(() -> new BusinessException(ErrorCode.CODE_EXPIRED));
    if (code == null || !hashUtil.matches(code.trim(), latest.getCodeHash())) {
      latest.recordFailure();
      int left = VerificationCode.MAX_FAILS - latest.getFailCount();
      if (left <= 0) {
        throw new BusinessException(ErrorCode.CODE_EXPIRED, "인증번호를 5번 잘못 입력했어요. 인증번호를 다시 받아 주세요.");
      }
      throw new BusinessException(ErrorCode.CODE_INVALID, "인증번호가 맞지 않아요. (" + left + "번 남음)");
    }
    latest.markVerified(now);
  }

  /**
   * 번호를 확인하고 바로 사용한 것으로 남긴다. 비밀번호 재설정처럼 확인과 사용이 한 번에 일어날 때 쓴다 (USR-06, SEC-05). 틀린 횟수는 {@link
   * #verify}처럼 남는다.
   */
  @Transactional(noRollbackFor = BusinessException.class)
  public void verifyAndUse(String email, VerificationPurpose purpose, String code) {
    verify(email, purpose, code);
    repository
        .findFirstByEmailAndPurposeOrderByIdDesc(email, purpose)
        .ifPresent(c -> c.markUsed(now()));
  }

  /**
   * 가입 마지막 단계에서 인증을 마쳤는지 서버가 다시 확인하고, 그 번호를 사용한 것으로 남긴다 (USR-02).
   *
   * @throws BusinessException 인증하지 않았거나, 이미 썼거나, 인증한 지 30분이 지났으면 EMAIL_NOT_VERIFIED
   */
  @Transactional
  public void consumeVerified(String email, VerificationPurpose purpose) {
    LocalDateTime now = now();
    VerificationCode latest =
        repository
            .findFirstByEmailAndPurposeOrderByIdDesc(email, purpose)
            .filter(c -> c.getVerifiedAt() != null && c.getUsedAt() == null)
            .filter(c -> c.getVerifiedAt().isAfter(now.minus(VERIFIED_VALID_FOR)))
            .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED));
    latest.markUsed(now);
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
