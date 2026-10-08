package com.blog.blog.domain;

import com.blog.auth.domain.User;
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
 * 블로그 블랙리스트 (blog_blacklist, T073, BLG-11, D-34, D-55). 강제 퇴장한 회원의 이름·이메일·전화번호 해시. 고치거나 지우지 않고 해제
 * 문의(BLG-12)를 받아들일 때만 {@code released_at}을 채운다.
 */
@Entity
@Table(name = "blog_blacklist")
public class BlogBlacklist {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blog_id", nullable = false)
  private Blog blog;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  private User user;

  @Column(name = "name_hash", nullable = false, length = 100)
  private String nameHash;

  @Column(name = "email_hash", nullable = false, length = 100)
  private String emailHash;

  @Column(name = "phone_hash", nullable = false, length = 100)
  private String phoneHash;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "registered_by", nullable = false)
  private User registeredBy;

  @Column(name = "released_at")
  private LocalDateTime releasedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "released_by")
  private User releasedBy;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected BlogBlacklist() {}

  public BlogBlacklist(
      Blog blog,
      User user,
      String nameHash,
      String emailHash,
      String phoneHash,
      User registeredBy) {
    this.blog = blog;
    this.user = user;
    this.nameHash = nameHash;
    this.emailHash = emailHash;
    this.phoneHash = phoneHash;
    this.registeredBy = registeredBy;
  }

  public boolean isActive() {
    return releasedAt == null;
  }

  public void release(User by, LocalDateTime now) {
    this.releasedBy = by;
    this.releasedAt = now;
  }

  public Long getId() {
    return id;
  }

  public Blog getBlog() {
    return blog;
  }

  public User getUser() {
    return user;
  }

  public String getNameHash() {
    return nameHash;
  }

  public String getEmailHash() {
    return emailHash;
  }

  public String getPhoneHash() {
    return phoneHash;
  }

  public User getRegisteredBy() {
    return registeredBy;
  }

  public LocalDateTime getReleasedAt() {
    return releasedAt;
  }

  public User getReleasedBy() {
    return releasedBy;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
