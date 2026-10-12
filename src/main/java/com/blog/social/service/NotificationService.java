package com.blog.social.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.social.domain.Notification;
import com.blog.social.domain.NotificationSetting;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.repository.NotificationRepository;
import com.blog.social.repository.NotificationSettingRepository;
import com.blog.social.repository.UserBlockRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 만들기 공통 기능 (T026, SOC-04, 3.6). 다른 기능은 알림을 직접 저장하지 않고 여기를 부른다.
 *
 * <ul>
 *   <li>끌 수 있는 종류를 회원이 껐으면 만들지 않는다. 이미 받은 알림은 그대로 둔다.
 *   <li>끌 수 없는 종류(위임 요청, 폐쇄 예정, 내 글 삭제됨, 제재 등)는 설정과 상관없이 항상 만든다.
 *   <li>자기 행동으로 자기에게 가는 알림(내 글에 내가 댓글)은 만들지 않는다.
 *   <li>끌 수 있는 종류는 받는 회원이 차단한 회원이 일으켰으면 만들지 않는다 (SOC-05).
 *   <li>부른 쪽의 트랜잭션 안에서 저장되므로, 댓글 저장이 실패하면 알림도 남지 않는다.
 * </ul>
 */
@Service
public class NotificationService {

  private final NotificationRepository notificationRepository;
  private final NotificationSettingRepository settingRepository;
  private final UserRepository userRepository;
  private final UserBlockRepository blockRepository;

  public NotificationService(
      NotificationRepository notificationRepository,
      NotificationSettingRepository settingRepository,
      UserRepository userRepository,
      UserBlockRepository blockRepository) {
    this.notificationRepository = notificationRepository;
    this.settingRepository = settingRepository;
    this.userRepository = userRepository;
    this.blockRepository = blockRepository;
  }

  /**
   * 한 명에게 알림을 만든다.
   *
   * @param receiverId 받는 회원
   * @param actorId 알림을 일으킨 회원. 시스템 알림은 null
   * @param targetType 누르면 갈 대상 종류. 없으면 null
   * @param targetId 대상 번호. 없으면 null
   * @param message 표시할 문구. 255자를 넘으면 자른다
   * @return 만든 알림. 끈 종류이거나 자기 자신에게 가는 알림이면 비어 있다
   */
  @Transactional
  public Optional<Notification> notify(
      Long receiverId,
      Long actorId,
      NotificationType type,
      NotificationTargetType targetType,
      Long targetId,
      String message) {
    List<Notification> created =
        notifyAll(List.of(receiverId), actorId, type, targetType, targetId, message);
    return created.stream().findFirst();
  }

  /**
   * 여러 명에게 같은 알림을 만든다. 폐쇄 예정처럼 멤버·구독자 전원에게 보낼 때 쓴다. 같은 회원이 두 번 들어 있어도 한 번만 만든다.
   *
   * @return 만든 알림들
   */
  @Transactional
  public List<Notification> notifyAll(
      Collection<Long> receiverIds,
      Long actorId,
      NotificationType type,
      NotificationTargetType targetType,
      Long targetId,
      String message) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(message, "message");
    Set<Long> receivers = new LinkedHashSet<>(receiverIds);
    receivers.remove(null);
    if (actorId != null) {
      receivers.remove(actorId);
    }
    if (receivers.isEmpty()) {
      return List.of();
    }
    if (type.isOptional()) {
      receivers.removeAll(new HashSet<>(settingRepository.findDisabledUserIds(type, receivers)));
      if (actorId != null && !receivers.isEmpty()) {
        receivers.removeAll(new HashSet<>(blockRepository.findBlockersOf(actorId, receivers)));
      }
    }
    User actor = actorId == null ? null : userRepository.getReferenceById(actorId);
    List<Notification> notifications = new ArrayList<>(receivers.size());
    for (Long receiverId : receivers) {
      notifications.add(
          Notification.create(
              userRepository.getReferenceById(receiverId),
              actor,
              type,
              targetType,
              targetId,
              message));
    }
    return notificationRepository.saveAll(notifications);
  }

  /** 이 회원이 이 종류의 새 알림을 받는지. 끌 수 없는 종류는 늘 true. */
  @Transactional(readOnly = true)
  public boolean isEnabled(Long userId, NotificationType type) {
    if (!type.isOptional()) {
      return true;
    }
    return settingRepository
        .findByUserIdAndType(userId, type)
        .map(NotificationSetting::isEnabled)
        .orElse(true);
  }

  /** 끌 수 있는 종류를 켜거나 끈다. 끌 수 없는 종류면 IllegalArgumentException (설정 API가 400으로 바꾼다). */
  @Transactional
  public void setEnabled(Long userId, NotificationType type, boolean enabled) {
    if (!type.isOptional()) {
      throw new IllegalArgumentException("Notification type cannot be turned off: " + type);
    }
    settingRepository
        .findByUserIdAndType(userId, type)
        .ifPresentOrElse(
            s -> s.setEnabled(enabled),
            () ->
                settingRepository.save(
                    new NotificationSetting(
                        userRepository.getReferenceById(userId), type, enabled)));
  }
}
