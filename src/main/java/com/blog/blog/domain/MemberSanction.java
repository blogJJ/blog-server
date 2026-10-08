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
import java.util.Set;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 멤버 경고·정지·강제 퇴장 기록 (member_sanctions, T072, BLG-13). 정지 중인지는 {@code blog_members.suspended_until}이
 * 정하고, 이 표는 이력과 사유를 남긴다. 1년 뒤 지운다.
 */
@Entity
@Table(name = "member_sanctions")
public class MemberSanction {

  /** 정지 기간 선택지. null은 영구 (D-57). */
  public static final Set<Integer> SUSPEND_DAYS = Set.of(3, 14, 30);

  public static final int REASON_MAX = 500;

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
  private SanctionType type;

  /** 3, 14, 30. 영구 정지와 정지가 아닌 제재는 NULL */
  @JdbcTypeCode(SqlTypes.SMALLINT)
  @Column(name = "suspend_days")
  private Integer suspendDays;

  @Column(name = "ends_at")
  private LocalDateTime endsAt;

  @Column(nullable = false, length = 500)
  private String reason;

  /** 신고를 처리하며 준 제재면 그 신고 번호 */
  @Column(name = "report_id")
  private Long reportId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "issued_by", nullable = false)
  private User issuedBy;

  @Column(name = "released_at")
  private LocalDateTime releasedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "released_by")
  private User releasedBy;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected MemberSanction() {}

  public MemberSanction(
      Blog blog,
      User user,
      SanctionType type,
      Integer suspendDays,
      LocalDateTime endsAt,
      String reason,
      Long reportId,
      User issuedBy) {
    this.blog = blog;
    this.user = user;
    this.type = type;
    this.suspendDays = suspendDays;
    this.endsAt = endsAt;
    this.reason = reason;
    this.reportId = reportId;
    this.issuedBy = issuedBy;
  }

  public void release(User by, LocalDateTime now) {
    this.releasedBy = by;
    this.releasedAt = now;
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

  public SanctionType getType() {
    return type;
  }

  public Integer getSuspendDays() {
    return suspendDays;
  }

  public LocalDateTime getEndsAt() {
    return endsAt;
  }

  public String getReason() {
    return reason;
  }

  public Long getReportId() {
    return reportId;
  }

  public User getIssuedBy() {
    return issuedBy;
  }

  public LocalDateTime getReleasedAt() {
    return releasedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
