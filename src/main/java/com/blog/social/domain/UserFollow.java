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

/** 회원 팔로우 (user_follows, T100, SOC-01). 한 쌍에 한 행. 다시 누르면 행을 지운다. */
@Entity
@Table(
    name = "user_follows",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_user_follows",
            columnNames = {"follower_id", "followee_id"}))
public class UserFollow {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "follower_id", nullable = false)
  private User follower;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "followee_id", nullable = false)
  private User followee;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected UserFollow() {}

  public Long getId() {
    return id;
  }

  public User getFollower() {
    return follower;
  }

  public User getFollowee() {
    return followee;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
