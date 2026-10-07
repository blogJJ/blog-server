package com.blog.blog.domain;

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

/** 부블로그장 권한 (blog_manager_permissions). 부블로그장인 멤버 행에 권한마다 한 행. */
@Entity
@Table(
    name = "blog_manager_permissions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_manager_perm",
            columnNames = {"blog_member_id", "permission"}))
public class BlogManagerPermission {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blog_member_id", nullable = false)
  private BlogMember blogMember;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ManagerPermission permission;

  @CreationTimestamp
  @Column(name = "granted_at", nullable = false, updatable = false)
  private LocalDateTime grantedAt;

  protected BlogManagerPermission() {}

  public BlogManagerPermission(BlogMember blogMember, ManagerPermission permission) {
    this.blogMember = blogMember;
    this.permission = permission;
  }

  public Long getId() {
    return id;
  }

  public BlogMember getBlogMember() {
    return blogMember;
  }

  public ManagerPermission getPermission() {
    return permission;
  }

  public LocalDateTime getGrantedAt() {
    return grantedAt;
  }
}
