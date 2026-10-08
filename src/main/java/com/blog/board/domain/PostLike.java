package com.blog.board.domain;

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

/** 좋아요 (post_likes, T060, BRD-06). 한 회원이 한 글에 한 번. 다시 누르면 행을 지운다. */
@Entity
@Table(
    name = "post_likes",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_post_likes",
            columnNames = {"post_id", "user_id"}))
public class PostLike {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "post_id", nullable = false)
  private Post post;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected PostLike() {}

  public Long getId() {
    return id;
  }

  public Post getPost() {
    return post;
  }

  public User getUser() {
    return user;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
