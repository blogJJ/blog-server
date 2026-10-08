package com.blog.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 이메일 찾기 결과의 임시 토큰 (account_find_tokens, T095, USR-08, D-26). 결과 화면의 [비밀번호 재설정]은 회원 번호 대신 이 토큰으로
 * 요청한다. 원문은 화면에만 있고 DB에는 해시만 둔다.
 */
@Entity
@Table(name = "account_find_tokens")
public class AccountFindToken {

  /** 이 시간 안에 인증번호를 요청해야 한다 */
  public static final Duration TTL = Duration.ofMinutes(10);

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "token_hash", nullable = false, length = 100, unique = true)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected AccountFindToken() {}

  public AccountFindToken(User user, String tokenHash, LocalDateTime now) {
    this.user = user;
    this.tokenHash = tokenHash;
    this.expiresAt = now.plus(TTL);
  }

  public boolean isActiveAt(LocalDateTime now) {
    return expiresAt.isAfter(now);
  }

  public Long getId() {
    return id;
  }

  public User getUser() {
    return user;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
