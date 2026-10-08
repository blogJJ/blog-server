package com.blog.social.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.social.domain.Notification;
import com.blog.social.domain.NotificationTab;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.repository.NotificationRepository;
import com.blog.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 알림 만들기 규칙: 끈 종류는 만들지 않고, 끌 수 없는 종류는 항상 만든다 (T025, T026, 3.6, US8). */
@IntegrationTest
class NotificationServiceTest {

  @Autowired NotificationService notificationService;
  @Autowired NotificationRepository notificationRepository;
  @Autowired UserRepository userRepository;
  @Autowired JdbcTemplate jdbc;

  User writer;
  User commenter;
  User subscriber;

  @BeforeEach
  void setUp() {
    writer = newUser("wr");
    commenter = newUser("cm");
    subscriber = newUser("sb");
  }

  @Test
  void createsNotificationWithTabFromType() {
    Notification n =
        notificationService
            .notify(
                writer.getId(),
                commenter.getId(),
                NotificationType.COMMENT,
                NotificationTargetType.POST,
                10L,
                "새 댓글이 달렸어요.")
            .orElseThrow();

    Notification saved = notificationRepository.findById(n.getId()).orElseThrow();
    assertThat(saved.getTab()).isEqualTo(NotificationTab.COMMENT);
    assertThat(saved.getType()).isEqualTo(NotificationType.COMMENT);
    assertThat(saved.getTargetId()).isEqualTo(10L);
    assertThat(saved.isRead()).isFalse();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(notificationRepository.countByReceiverIdAndReadFalse(writer.getId())).isEqualTo(1);
  }

  @Test
  void turnedOffTypeIsNotCreatedButOthersStillAre() {
    notificationService.setEnabled(writer.getId(), NotificationType.COMMENT, false);

    assertThat(
            notificationService.notify(
                writer.getId(), commenter.getId(), NotificationType.COMMENT, null, null, "댓글"))
        .isEmpty();
    assertThat(
            notificationService.notify(
                writer.getId(), commenter.getId(), NotificationType.POST_LIKE, null, null, "좋아요"))
        .isPresent();
    assertThat(notificationService.isEnabled(writer.getId(), NotificationType.COMMENT)).isFalse();

    // 다시 켜면 만든다
    notificationService.setEnabled(writer.getId(), NotificationType.COMMENT, true);
    assertThat(
            notificationService.notify(
                writer.getId(), commenter.getId(), NotificationType.COMMENT, null, null, "댓글"))
        .isPresent();
  }

  @Test
  void requiredTypesCannotBeTurnedOffAndAreAlwaysCreated() {
    assertThatThrownBy(
            () ->
                notificationService.setEnabled(
                    writer.getId(), NotificationType.BLOG_CLOSING, false))
        .isInstanceOf(IllegalArgumentException.class);
    // 혹시 DB에 꺼진 행이 있어도 끌 수 없는 종류는 만든다
    jdbc.update(
        "insert into notification_settings (user_id, type, enabled) values (?, 'BLOG_CLOSING', false)",
        writer.getId());

    assertThat(notificationService.isEnabled(writer.getId(), NotificationType.BLOG_CLOSING))
        .isTrue();
    assertThat(
            notificationService.notifyAll(
                List.of(writer.getId(), subscriber.getId()),
                null,
                NotificationType.BLOG_CLOSING,
                NotificationTargetType.BLOG,
                1L,
                "블로그가 7일 뒤 폐쇄돼요."))
        .hasSize(2);
  }

  @Test
  void notifyAllSkipsActorDuplicatesAndTurnedOff() {
    notificationService.setEnabled(subscriber.getId(), NotificationType.NOTICE, false);

    List<Notification> created =
        notificationService.notifyAll(
            List.of(writer.getId(), writer.getId(), commenter.getId(), subscriber.getId()),
            commenter.getId(),
            NotificationType.NOTICE,
            null,
            null,
            "공지");

    assertThat(created).extracting(n -> n.getReceiver().getId()).containsExactly(writer.getId());
  }

  @Test
  void ownActionDoesNotNotifySelf() {
    assertThat(
            notificationService.notify(
                writer.getId(), writer.getId(), NotificationType.COMMENT, null, null, "댓글"))
        .isEmpty();
  }

  @Test
  void longMessageIsCutToColumnLength() {
    Notification n =
        notificationService
            .notify(writer.getId(), null, NotificationType.NOTICE, null, null, "가".repeat(400))
            .orElseThrow();

    assertThat(n.getMessage()).hasSize(Notification.MAX_MESSAGE_LENGTH).endsWith("…");
  }

  private User newUser(String prefix) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            prefix + unique + "@example.com",
            "hash",
            "이름",
            prefix + unique,
            "01012345678",
            LocalDateTime.now()));
  }
}
