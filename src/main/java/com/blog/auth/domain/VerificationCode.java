package com.blog.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 이메일 인증번호 (verification_codes, T028, USR-02). 가입 전이라 회원 번호 대신 이메일로 찾는다. 번호는 해시로만 저장한다.
 *
 * <p>쓸 수 있는 번호: 만료 전이고, 5번 넘게 틀리지 않았고, 아직 쓰지 않은 번호. 새 번호를 보내면 이전 번호는 만료시킨다.
 */
@Entity
@Table(name = "verification_codes")
public class VerificationCode {

  /** 이만큼 틀리면 번호가 무효다 */
  public static final int MAX_FAILS = 5;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String email;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private VerificationPurpose purpose;

  @Column(name = "code_hash", nullable = false, length = 100)
  private String codeHash;

  @JdbcTypeCode(SqlTypes.TINYINT)
  @Column(name = "fail_count", nullable = false)
  private int failCount;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "verified_at")
  private LocalDateTime verifiedAt;

  @Column(name = "used_at")
  private LocalDateTime usedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected VerificationCode() {}

  public VerificationCode(
      String email, VerificationPurpose purpose, String codeHash, LocalDateTime now) {
    this.email = email;
    this.purpose = purpose;
    this.codeHash = codeHash;
    this.expiresAt = now.plus(purpose.getTtl());
  }

  /** 아직 입력할 수 있는 번호인지 */
  public boolean isUsableAt(LocalDateTime now) {
    return usedAt == null && failCount < MAX_FAILS && expiresAt.isAfter(now);
  }

  public void recordFailure() {
    failCount++;
  }

  public void markVerified(LocalDateTime now) {
    this.verifiedAt = now;
  }

  public void markUsed(LocalDateTime now) {
    this.usedAt = now;
  }

  public Long getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public VerificationPurpose getPurpose() {
    return purpose;
  }

  public String getCodeHash() {
    return codeHash;
  }

  public int getFailCount() {
    return failCount;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public LocalDateTime getVerifiedAt() {
    return verifiedAt;
  }

  public LocalDateTime getUsedAt() {
    return usedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
