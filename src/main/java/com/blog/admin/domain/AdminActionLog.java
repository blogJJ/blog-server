package com.blog.admin.domain;

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

/** 관리자 활동 기록 (admin_action_logs, T115, ADM-06, SEC-09). 누가 언제 무엇을 했는지 남기고 1년 뒤 지운다 (D-60). */
@Entity
@Table(name = "admin_action_logs")
public class AdminActionLog {

  public static final int REASON_MAX = 500;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "admin_id", nullable = false)
  private User admin;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private AdminAction action;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", nullable = false)
  private AdminTargetType targetType;

  @Column(name = "target_id", nullable = false)
  private Long targetId;

  @Column(length = 500)
  private String reason;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected AdminActionLog() {}

  public AdminActionLog(
      User admin, AdminAction action, AdminTargetType targetType, Long targetId, String reason) {
    this.admin = admin;
    this.action = action;
    this.targetType = targetType;
    this.targetId = targetId;
    this.reason = reason;
  }

  public Long getId() {
    return id;
  }

  public User getAdmin() {
    return admin;
  }

  public AdminAction getAction() {
    return action;
  }

  public AdminTargetType getTargetType() {
    return targetType;
  }

  public Long getTargetId() {
    return targetId;
  }

  public String getReason() {
    return reason;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
