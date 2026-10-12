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

/**
 * 폐쇄 알림 발송 기록 (blog_close_notices, T086, BLG-09). 폐쇄 예정 시각과 단계마다 한 행이라 배치를 두 번 돌리거나 밀린 날을 몰아서 처리해도
 * 같은 알림이 두 번 가지 않는다 (SC-005). 철회 뒤 다시 폐쇄를 누르면 예정 시각이 새 값이 되어 새로 센다.
 */
@Entity
@Table(
    name = "blog_close_notices",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_close_notice",
            columnNames = {"blog_id", "close_scheduled_at", "stage"}))
public class BlogCloseNotice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blog_id", nullable = false)
  private Blog blog;

  @Column(name = "close_scheduled_at", nullable = false)
  private LocalDateTime closeScheduledAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private CloseNoticeStage stage;

  @CreationTimestamp
  @Column(name = "sent_at", nullable = false, updatable = false)
  private LocalDateTime sentAt;

  protected BlogCloseNotice() {}

  public BlogCloseNotice(Blog blog, LocalDateTime closeScheduledAt, CloseNoticeStage stage) {
    this.blog = blog;
    this.closeScheduledAt = closeScheduledAt;
    this.stage = stage;
  }

  public Long getId() {
    return id;
  }

  public Blog getBlog() {
    return blog;
  }

  public LocalDateTime getCloseScheduledAt() {
    return closeScheduledAt;
  }

  public CloseNoticeStage getStage() {
    return stage;
  }

  public LocalDateTime getSentAt() {
    return sentAt;
  }
}
