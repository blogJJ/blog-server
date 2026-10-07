package com.blog.blog.domain;

import com.blog.auth.domain.User;
import com.blog.common.domain.Suspensions;
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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** 블로그 멤버 (blog_members). 블로그장·부블로그장·멤버 모두 한 행씩 있다. */
@Entity
@Table(
    name = "blog_members",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_blog_members",
            columnNames = {"blog_id", "user_id"}))
public class BlogMember {

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
  private BlogMemberRole role = BlogMemberRole.MEMBER;

  /** 부블로그장이 된 시각. 자동 위임 때 먼저 된 사람이 우선 (ADM-07). */
  @Column(name = "manager_since")
  private LocalDateTime managerSince;

  /** 이 블로그에서의 정지 끝나는 시각. 영구 정지는 9999-12-31 (BLG-13). */
  @Column(name = "suspended_until")
  private LocalDateTime suspendedUntil;

  /** 이 블로그에서 받은 정지 횟수. 3번이면 블로그장 화면에 표시. */
  @Column(name = "suspension_count", nullable = false)
  private int suspensionCount;

  @CreationTimestamp
  @Column(name = "joined_at", nullable = false, updatable = false)
  private LocalDateTime joinedAt;

  protected BlogMember() {}

  public BlogMember(Blog blog, User user, BlogMemberRole role) {
    this.blog = blog;
    this.user = user;
    this.role = role;
  }

  public boolean isOwner() {
    return role == BlogMemberRole.OWNER;
  }

  public boolean isManager() {
    return role == BlogMemberRole.MANAGER;
  }

  public boolean isSuspendedAt(LocalDateTime now) {
    return Suspensions.isSuspended(suspendedUntil, now);
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

  public BlogMemberRole getRole() {
    return role;
  }

  public LocalDateTime getManagerSince() {
    return managerSince;
  }

  public LocalDateTime getSuspendedUntil() {
    return suspendedUntil;
  }

  public int getSuspensionCount() {
    return suspensionCount;
  }

  public LocalDateTime getJoinedAt() {
    return joinedAt;
  }
}
