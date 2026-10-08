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
 * 승인제 블로그의 참여 신청 (blog_join_requests, T042, BLG-04, BLG-05). 거절되면 handled_at + 7일 뒤에 다시 신청할 수 있다.
 */
@Entity
@Table(name = "blog_join_requests")
public class BlogJoinRequest {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blog_id", nullable = false)
  private Blog blog;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private JoinRequestStatus status = JoinRequestStatus.PENDING;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "handled_by")
  private User handledBy;

  @Column(name = "handled_at")
  private LocalDateTime handledAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected BlogJoinRequest() {}

  public BlogJoinRequest(Blog blog, User user) {
    this.blog = blog;
    this.user = user;
  }

  public boolean isPending() {
    return status == JoinRequestStatus.PENDING;
  }

  public void approve(User handler, LocalDateTime now) {
    handle(JoinRequestStatus.APPROVED, handler, now);
  }

  public void reject(User handler, LocalDateTime now) {
    handle(JoinRequestStatus.REJECTED, handler, now);
  }

  /** 신청한 사람이 스스로 취소 */
  public void cancel(LocalDateTime now) {
    handle(JoinRequestStatus.CANCELED, null, now);
  }

  private void handle(JoinRequestStatus next, User handler, LocalDateTime now) {
    this.status = next;
    this.handledBy = handler;
    this.handledAt = now;
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

  public JoinRequestStatus getStatus() {
    return status;
  }

  public User getHandledBy() {
    return handledBy;
  }

  public LocalDateTime getHandledAt() {
    return handledAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
