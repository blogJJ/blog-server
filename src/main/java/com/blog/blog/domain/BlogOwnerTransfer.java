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
import java.time.Duration;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 블로그장 위임 요청 (blog_owner_transfers, T086, BLG-08). 받는 멤버가 수락해야 넘어가고(D-16), 7일 안에 응답이 없으면 04:00 배치가
 * EXPIRED로 바꾼다(D-31). 폐쇄 버튼을 누르면 CANCELED.
 */
@Entity
@Table(name = "blog_owner_transfers")
public class BlogOwnerTransfer {

  public static final Duration VALID_FOR = Duration.ofDays(7);

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blog_id", nullable = false)
  private Blog blog;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "from_user_id", nullable = false)
  private User fromUser;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "to_user_id", nullable = false)
  private User toUser;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TransferStatus status = TransferStatus.PENDING;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "responded_at")
  private LocalDateTime respondedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected BlogOwnerTransfer() {}

  public BlogOwnerTransfer(Blog blog, User fromUser, User toUser, LocalDateTime now) {
    this.blog = blog;
    this.fromUser = fromUser;
    this.toUser = toUser;
    this.expiresAt = now.plus(VALID_FOR);
  }

  public boolean isPending() {
    return status == TransferStatus.PENDING;
  }

  /** 대기 중이지만 7일이 지났다. 배치가 돌기 전이라도 수락할 수 없다. */
  public boolean isExpiredAt(LocalDateTime now) {
    return !expiresAt.isAfter(now);
  }

  public void accept(LocalDateTime now) {
    finish(TransferStatus.ACCEPTED, now);
  }

  public void reject(LocalDateTime now) {
    finish(TransferStatus.REJECTED, now);
  }

  /** 폐쇄 버튼, 받는 멤버의 블로그 탈퇴·회원 탈퇴로 취소 */
  public void cancel(LocalDateTime now) {
    finish(TransferStatus.CANCELED, now);
  }

  public void expire(LocalDateTime now) {
    finish(TransferStatus.EXPIRED, now);
  }

  private void finish(TransferStatus next, LocalDateTime now) {
    this.status = next;
    this.respondedAt = now;
  }

  public Long getId() {
    return id;
  }

  public Blog getBlog() {
    return blog;
  }

  public User getFromUser() {
    return fromUser;
  }

  public User getToUser() {
    return toUser;
  }

  public TransferStatus getStatus() {
    return status;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public LocalDateTime getRespondedAt() {
    return respondedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
