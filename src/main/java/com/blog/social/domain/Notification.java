package com.blog.social.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 알림 (notifications, SOC-04). 만들기는 NotificationService로만 한다. 보관 기간(30일 또는 7일)이 지나면 04:00 배치가 지운다.
 */
@Entity
@Table(name = "notifications")
public class Notification {

  /** message 컬럼 길이 */
  public static final int MAX_MESSAGE_LENGTH = 255;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "receiver_id", nullable = false)
  private User receiver;

  /** 알림을 일으킨 회원. 시스템 알림(폐쇄 예정 등)은 null */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "actor_id")
  private User actor;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private NotificationTab tab;

  /** VARCHAR(40) 컬럼이라 MySQL ENUM이 아닌 문자열로 다룬다. 새 종류를 더해도 DB를 고치지 않는다 */
  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 40)
  private NotificationType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_type")
  private NotificationTargetType targetType;

  @Column(name = "target_id")
  private Long targetId;

  @Column(nullable = false, length = MAX_MESSAGE_LENGTH)
  private String message;

  @Column(name = "is_read", nullable = false)
  private boolean read;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected Notification() {}

  Notification(
      User receiver,
      User actor,
      NotificationType type,
      NotificationTargetType targetType,
      Long targetId,
      String message) {
    this.receiver = receiver;
    this.actor = actor;
    this.type = type;
    this.tab = type.getTab();
    this.targetType = targetType;
    this.targetId = targetId;
    this.message = message;
  }

  /** 문구가 컬럼보다 길면 잘라서 만든다. */
  public static Notification create(
      User receiver,
      User actor,
      NotificationType type,
      NotificationTargetType targetType,
      Long targetId,
      String message) {
    String text =
        message.length() > MAX_MESSAGE_LENGTH
            ? message.substring(0, MAX_MESSAGE_LENGTH - 1) + "…"
            : message;
    return new Notification(receiver, actor, type, targetType, targetId, text);
  }

  public void markRead() {
    this.read = true;
  }

  public Long getId() {
    return id;
  }

  public User getReceiver() {
    return receiver;
  }

  public User getActor() {
    return actor;
  }

  public NotificationTab getTab() {
    return tab;
  }

  public NotificationType getType() {
    return type;
  }

  public NotificationTargetType getTargetType() {
    return targetType;
  }

  public Long getTargetId() {
    return targetId;
  }

  public String getMessage() {
    return message;
  }

  public boolean isRead() {
    return read;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
