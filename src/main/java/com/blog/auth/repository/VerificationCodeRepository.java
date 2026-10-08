package com.blog.auth.repository;

import com.blog.auth.domain.VerificationCode;
import com.blog.auth.domain.VerificationPurpose;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Long> {

  /** 이 이메일·쓰임새로 가장 최근에 보낸 번호 */
  Optional<VerificationCode> findFirstByEmailAndPurposeOrderByIdDesc(
      String email, VerificationPurpose purpose);

  /** 새 번호를 보낼 때 아직 살아 있는 이전 번호를 모두 만료시킨다 */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update VerificationCode v set v.expiresAt = :now"
          + " where v.email = :email and v.purpose = :purpose and v.usedAt is null"
          + " and v.expiresAt > :now")
  int expireActive(
      @Param("email") String email,
      @Param("purpose") VerificationPurpose purpose,
      @Param("now") LocalDateTime now);
}
