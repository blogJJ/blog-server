package com.blog.social.domain;

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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** 회원 차단 (user_blocks, T100, SOC-05). 차단한 회원만 상대의 글·댓글이 안 보인다. */
@Entity
@Table(
    name = "user_blocks",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_user_blocks",
            columnNames = {"blocker_id", "blocked_id"}))
public class UserBlock {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blocker_id", nullable = false)
  private User blocker;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blocked_id", nullable = false)
  private User blocked;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected UserBlock() {}

  public Long getId() {
    return id;
  }

  public User getBlocker() {
    return blocker;
  }

  public User getBlocked() {
    return blocked;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
