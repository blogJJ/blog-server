package com.blog.social.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.web.PageResponse;
import com.blog.social.domain.Notification;
import com.blog.social.domain.NotificationTab;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.repository.NotificationRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림함 (T111~T113, SOC-04, 3.6). 목록·읽음·전체 삭제·안 읽은 수와 알림 설정.
 *
 * <ul>
 *   <li>보관 기간(30일 또는 7일)이 지난 알림은 목록과 안 읽은 수에서 바로 빠진다. 행은 새벽 정리 배치(T090)가 지운다.
 *   <li>설정에는 끌 수 있는 알림만 나온다. 끄면 새 알림만 막고 받은 알림은 그대로 둔다.
 * </ul>
 */
@Service
public class NotificationInboxService {

  public static final int PAGE_SIZE = 20;

  /** 설정 화면에 보일 이름. 끌 수 있는 알림만 (3.6 알림 종류 표) */
  static final Map<NotificationType, String> LABELS = labels();

  public record Row(
      Long id,
      String tab,
      String type,
      String message,
      String actorNickname,
      String link,
      boolean read,
      LocalDateTime createdAt) {}

  public record SettingRow(String type, String tab, String label, boolean enabled) {}

  public record Settings(int keepDays, List<SettingRow> items) {}

  private final NotificationRepository notificationRepository;
  private final NotificationService notificationService;
  private final UserRepository userRepository;
  private final EntityManager entityManager;
  private final Clock clock;

  public NotificationInboxService(
      NotificationRepository notificationRepository,
      NotificationService notificationService,
      UserRepository userRepository,
      EntityManager entityManager,
      Clock clock) {
    this.notificationRepository = notificationRepository;
    this.notificationService = notificationService;
    this.userRepository = userRepository;
    this.entityManager = entityManager;
    this.clock = clock;
  }

  /**
   * @param tab null이면 전체 탭
   * @param page 1부터
   */
  @Transactional(readOnly = true)
  public PageResponse<Row> inbox(Long userId, NotificationTab tab, int page) {
    return PageResponse.of(
        notificationRepository.findInbox(
            userId, tab, since(userId), PageRequest.of(Math.max(page, 1) - 1, PAGE_SIZE)),
        this::rows);
  }

  @Transactional(readOnly = true)
  public long unreadCount(Long userId) {
    return notificationRepository.countUnread(userId, since(userId));
  }

  /** 읽음 처리. 남의 알림이면 NOT_FOUND */
  @Transactional
  public void markRead(Long userId, Long notificationId) {
    notificationRepository
        .findByIdAndReceiverId(notificationId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND))
        .markRead();
  }

  @Transactional
  public void markAllRead(Long userId) {
    notificationRepository.markAllRead(userId);
  }

  /** 전체 삭제. tab을 주면 그 탭만 */
  @Transactional
  public int deleteAll(Long userId, NotificationTab tab) {
    return notificationRepository.deleteInbox(userId, tab);
  }

  @Transactional(readOnly = true)
  public Settings settings(Long userId) {
    User user = loadUser(userId);
    List<SettingRow> items =
        LABELS.entrySet().stream()
            .map(
                e ->
                    new SettingRow(
                        e.getKey().name(),
                        e.getKey().getTab().name(),
                        e.getValue(),
                        notificationService.isEnabled(userId, e.getKey())))
            .toList();
    return new Settings(user.getNotificationKeepDays(), items);
  }

  /**
   * 설정 저장. 보낸 종류만 바꾼다.
   *
   * @param keepDays 30 또는 7. null이면 그대로
   * @throws BusinessException 끌 수 없는 종류나 없는 종류, 30·7이 아닌 보관 기간이면 INVALID_INPUT
   */
  @Transactional
  public Settings update(Long userId, Integer keepDays, Map<String, Boolean> enabled) {
    User user = loadUser(userId);
    if (keepDays != null) {
      if (keepDays != 30 && keepDays != 7) {
        throw new BusinessException(ErrorCode.INVALID_INPUT, "알림 보관 기간은 30일이나 7일 중에서 골라 주세요.");
      }
      user.changeNotificationKeepDays(keepDays);
    }
    if (enabled != null) {
      enabled.forEach(
          (name, on) -> {
            NotificationType type =
                Arrays.stream(NotificationType.values())
                    .filter(t -> t.name().equals(name) && t.isOptional())
                    .findFirst()
                    .orElseThrow(
                        () -> new BusinessException(ErrorCode.INVALID_INPUT, "끌 수 없는 알림이에요."));
            if (on == null) {
              throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            notificationService.setEnabled(userId, type, on);
          });
    }
    return settings(userId);
  }

  private List<Row> rows(List<Notification> list) {
    Map<Long, String> postSlugs =
        slugs(
            "select p.id, p.blog.slug from Post p where p.id in :ids",
            idsOf(list, NotificationTargetType.POST));
    Map<Long, String> blogSlugs =
        slugs(
            "select b.id, b.slug from Blog b where b.id in :ids",
            idsOf(list, NotificationTargetType.BLOG));
    return list.stream()
        .map(
            n ->
                new Row(
                    n.getId(),
                    n.getTab().name(),
                    n.getType().name(),
                    n.getMessage(),
                    n.getActor() == null ? null : n.getActor().getNickname(),
                    link(n, postSlugs, blogSlugs),
                    n.isRead(),
                    n.getCreatedAt()))
        .toList();
  }

  /** 누르면 갈 화면. 대상이 없거나 지금은 갈 화면이 없으면 null */
  private static String link(
      Notification n, Map<Long, String> postSlugs, Map<Long, String> blogSlugs) {
    if (n.getTargetType() == null || n.getTargetId() == null) {
      return null;
    }
    return switch (n.getTargetType()) {
      case POST -> {
        String slug = postSlugs.get(n.getTargetId());
        yield slug == null ? null : "/blog/" + slug + "/posts/" + n.getTargetId();
      }
      case BLOG -> {
        String slug = blogSlugs.get(n.getTargetId());
        if (slug == null) {
          yield null;
        }
        yield n.getType() == NotificationType.JOIN_REQUEST
            ? "/blog/" + slug + "/admin"
            : "/blog/" + slug;
      }
      default -> null;
    };
  }

  private static Set<Long> idsOf(List<Notification> list, NotificationTargetType type) {
    return list.stream()
        .filter(n -> n.getTargetType() == type && n.getTargetId() != null)
        .map(Notification::getTargetId)
        .collect(Collectors.toSet());
  }

  private Map<Long, String> slugs(String jpql, Collection<Long> ids) {
    Map<Long, String> map = new HashMap<>();
    if (ids.isEmpty()) {
      return map;
    }
    for (Object[] row :
        entityManager.createQuery(jpql, Object[].class).setParameter("ids", ids).getResultList()) {
      if (row[1] != null) {
        map.put((Long) row[0], (String) row[1]);
      }
    }
    return map;
  }

  private LocalDateTime since(Long userId) {
    return LocalDateTime.now(clock).minusDays(loadUser(userId).getNotificationKeepDays());
  }

  private User loadUser(Long userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
  }

  private static Map<NotificationType, String> labels() {
    Map<NotificationType, String> map = new LinkedHashMap<>();
    map.put(NotificationType.COMMENT, "내 글에 댓글");
    map.put(NotificationType.REPLY, "내 댓글에 대댓글");
    map.put(NotificationType.POST_LIKE, "내 글 좋아요");
    map.put(NotificationType.FOLLOW, "새 팔로워");
    map.put(NotificationType.BLOG_SUBSCRIBE, "내 블로그 구독");
    map.put(NotificationType.JOIN_REQUEST, "참여 신청");
    map.put(NotificationType.JOIN_RESULT, "참여 승인·거절");
    map.put(NotificationType.REPORT_RESULT, "신고 처리 결과");
    map.put(NotificationType.NOTICE, "공지사항");
    return map;
  }
}
