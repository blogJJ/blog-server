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
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 로그인 한 번 (refresh_tokens, T029, SEC-04). 브라우저 쿠키에는 토큰 원문을, DB에는 해시만 둔다.
 *
 * <p>"로그인 유지"를 체크하면 로그인한 때부터 14일, 안 하면 마지막 활동부터 30분이 지나면 끝난다(ActivityPolicy가 30분을 늘린다). 로그아웃·비밀번호
 * 변경 때 revoked_at을 채운다. Access Token에 이 행의 번호가 들어 있어 폐기하면 바로 로그인이 풀린다.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "token_hash", nullable = false, length = 100, unique = true)
  private String tokenHash;

  @Column(name = "remember_me", nullable = false)
  private boolean rememberMe;

  @Column(name = "user_agent", length = 255)
  private String userAgent;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "revoked_at")
  private LocalDateTime revokedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected RefreshToken() {}

  public RefreshToken(
      User user, String tokenHash, boolean rememberMe, String userAgent, LocalDateTime expiresAt) {
    this.user = user;
    this.tokenHash = tokenHash;
    this.rememberMe = rememberMe;
    this.userAgent =
        userAgent == null || userAgent.length() <= 255 ? userAgent : userAgent.substring(0, 255);
    this.expiresAt = expiresAt;
  }

  /** 폐기되지 않았고 만료 전이면 true */
  public boolean isActiveAt(LocalDateTime now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  public void revoke(LocalDateTime now) {
    if (revokedAt == null) {
      revokedAt = now;
    }
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

  public boolean isRememberMe() {
    return rememberMe;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public LocalDateTime getRevokedAt() {
    return revokedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
