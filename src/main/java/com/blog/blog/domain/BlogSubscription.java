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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** 블로그 구독 (blog_subscriptions, T100, SOC-01). 블로그가 비공개로 바뀌어도 행은 남는다 (D-50). */
@Entity
@Table(
    name = "blog_subscriptions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_blog_subs",
            columnNames = {"blog_id", "user_id"}))
public class BlogSubscription {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blog_id", nullable = false)
  private Blog blog;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected BlogSubscription() {}

  public Long getId() {
    return id;
  }

  public Blog getBlog() {
    return blog;
  }

  public User getUser() {
    return user;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
