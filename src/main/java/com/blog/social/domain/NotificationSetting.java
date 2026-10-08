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
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 알림 설정 (notification_settings, 3.6). 끌 수 있는 알림 종류만 행을 둔다. 행이 없으면 켜짐이다. 끌 수 없는 종류는 행을 만들지 않는다. */
@Entity
@Table(
    name = "notification_settings",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_notif_settings",
            columnNames = {"user_id", "type"}))
public class NotificationSetting {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  /** VARCHAR(40) 컬럼이라 MySQL ENUM이 아닌 문자열로 다룬다. 새 종류를 더해도 DB를 고치지 않는다 */
  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 40)
  private NotificationType type;

  @Column(nullable = false)
  private boolean enabled;

  protected NotificationSetting() {}

  public NotificationSetting(User user, NotificationType type, boolean enabled) {
    if (!type.isOptional()) {
      throw new IllegalArgumentException("Notification type cannot be turned off: " + type);
    }
    this.user = user;
    this.type = type;
    this.enabled = enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Long getId() {
    return id;
  }

  public User getUser() {
    return user;
  }

  public NotificationType getType() {
    return type;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
