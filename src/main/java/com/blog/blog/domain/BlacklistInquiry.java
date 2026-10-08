package com.blog.blog.domain;

import com.blog.auth.domain.User;
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
 * 블랙리스트 해제 문의 (blacklist_inquiries, T073, BLG-12). 이름·전화번호가 강퇴된 사람과 같은지는 서버가 해시로 비교해 저장하고, 블로그장은 원문
 * 없이 그 결과만 본다.
 */
@Entity
@Table(name = "blacklist_inquiries")
public class BlacklistInquiry {

  public static final int MESSAGE_MAX = 500;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blacklist_id", nullable = false)
  private BlogBlacklist blacklist;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "name_match", nullable = false)
  private boolean nameMatch;

  @Column(name = "phone_match", nullable = false)
  private boolean phoneMatch;

  @Column(length = 500)
  private String message;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private InquiryStatus status = InquiryStatus.PENDING;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "handled_by")
  private User handledBy;

  @Column(name = "handled_at")
  private LocalDateTime handledAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected BlacklistInquiry() {}

  public BlacklistInquiry(
      BlogBlacklist blacklist, User user, boolean nameMatch, boolean phoneMatch, String message) {
    this.blacklist = blacklist;
    this.user = user;
    this.nameMatch = nameMatch;
    this.phoneMatch = phoneMatch;
    this.message = message;
  }

  public boolean isPending() {
    return status == InquiryStatus.PENDING;
  }

  public void handle(InquiryStatus result, User by, LocalDateTime now) {
    this.status = result;
    this.handledBy = by;
    this.handledAt = now;
  }

  public Long getId() {
    return id;
  }

  public BlogBlacklist getBlacklist() {
    return blacklist;
  }

  public User getUser() {
    return user;
  }

  public boolean isNameMatch() {
    return nameMatch;
  }

  public boolean isPhoneMatch() {
    return phoneMatch;
  }

  public String getMessage() {
    return message;
  }

  public InquiryStatus getStatus() {
    return status;
  }

  public LocalDateTime getHandledAt() {
    return handledAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
