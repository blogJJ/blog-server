package com.blog.social.domain;

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
 * 신고 (reports, T074, SOC-06, D-85). 대상은 {@code target_type} + {@code target_id}로 가리키고(FK 없음), 신고 당시
 * 내용을 {@code target_snapshot}에 남겨 원본이 지워져도 1년 동안 확인할 수 있다.
 */
@Entity
@Table(name = "reports")
public class Report {

  public static final int DETAIL_MAX = 500;
  public static final int SNAPSHOT_MAX = 1000;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "reporter_id", nullable = false)
  private User reporter;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", nullable = false)
  private ReportTargetType targetType;

  @Column(name = "target_id", nullable = false)
  private Long targetId;

  /** 블로그 안의 신고면 그 블로그. 메인 프로필 신고는 NULL */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "blog_id")
  private Blog blog;

  @Enumerated(EnumType.STRING)
  @Column(name = "handler_scope", nullable = false)
  private HandlerScope handlerScope;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ReportReason reason;

  @Column(length = 500)
  private String detail;

  @Column(name = "target_snapshot", nullable = false, length = 1000)
  private String targetSnapshot;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ReportStatus status = ReportStatus.PENDING;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "handled_by")
  private User handledBy;

  @Column(name = "handled_at")
  private LocalDateTime handledAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected Report() {}

  public Report(
      User reporter,
      ReportTargetType targetType,
      Long targetId,
      Blog blog,
      HandlerScope handlerScope,
      ReportReason reason,
      String detail,
      String targetSnapshot) {
    this.reporter = reporter;
    this.targetType = targetType;
    this.targetId = targetId;
    this.blog = blog;
    this.handlerScope = handlerScope;
    this.reason = reason;
    this.detail = detail;
    this.targetSnapshot = targetSnapshot;
  }

  public boolean isPending() {
    return status == ReportStatus.PENDING;
  }

  public void resolve(ReportStatus result, User by, LocalDateTime now) {
    this.status = result;
    this.handledBy = by;
    this.handledAt = now;
  }

  public Long getId() {
    return id;
  }

  public User getReporter() {
    return reporter;
  }

  public ReportTargetType getTargetType() {
    return targetType;
  }

  public Long getTargetId() {
    return targetId;
  }

  public Blog getBlog() {
    return blog;
  }

  public HandlerScope getHandlerScope() {
    return handlerScope;
  }

  public ReportReason getReason() {
    return reason;
  }

  public String getDetail() {
    return detail;
  }

  public String getTargetSnapshot() {
    return targetSnapshot;
  }

  public ReportStatus getStatus() {
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
