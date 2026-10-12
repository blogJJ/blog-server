package com.blog.admin.domain;

import com.blog.auth.domain.User;
import com.blog.blog.domain.Blog;
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
 * 블로그장 경고·권한 박탈 (owner_sanctions, T115, ADM-07). 경고는 블로그별로 세고 1년 지난 것은 빼며(D-97), 최근 1년 안에 3번이면 권한을
 * 박탈한다.
 */
@Entity
@Table(name = "owner_sanctions")
public class OwnerSanction {

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
  private OwnerSanctionType type;

  @Column(nullable = false, length = 500)
  private String reason;

  /** 신고를 처리하며 준 제재면 그 신고 번호 */
  @Column(name = "report_id")
  private Long reportId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "admin_id", nullable = false)
  private User admin;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected OwnerSanction() {}

  public OwnerSanction(
      Blog blog, User user, OwnerSanctionType type, String reason, Long reportId, User admin) {
    this.blog = blog;
    this.user = user;
    this.type = type;
    this.reason = reason;
    this.reportId = reportId;
    this.admin = admin;
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

  public OwnerSanctionType getType() {
    return type;
  }

  public String getReason() {
    return reason;
  }

  public Long getReportId() {
    return reportId;
  }

  public User getAdmin() {
    return admin;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
