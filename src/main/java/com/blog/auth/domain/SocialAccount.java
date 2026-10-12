package com.blog.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 소셜 로그인 연결 (user_social_accounts, T157, D-128). 회원 한 명은 제공자마다 하나만, 제공자 계정 하나는 회원 한 명에게만 연결된다.
 *
 * <p>제공자 계정은 이메일이 아니라 제공자의 회원 번호(구글 sub, 깃허브 id)로 찾는다. 이메일은 연결할 때 기록만 해 둔다.
 */
@Entity
@Table(name = "user_social_accounts")
public class SocialAccount {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SocialProvider provider;

  @Column(name = "provider_user_id", nullable = false, length = 100)
  private String providerUserId;

  @Column(name = "email_at_link", length = 100)
  private String emailAtLink;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected SocialAccount() {}

  public SocialAccount(
      User user, SocialProvider provider, String providerUserId, String emailAtLink) {
    this.user = user;
    this.provider = provider;
    this.providerUserId = providerUserId;
    this.emailAtLink = User.normalizeEmail(emailAtLink);
  }

  public Long getId() {
    return id;
  }

  public User getUser() {
    return user;
  }

  public SocialProvider getProvider() {
    return provider;
  }

  public String getProviderUserId() {
    return providerUserId;
  }

  public String getEmailAtLink() {
    return emailAtLink;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
