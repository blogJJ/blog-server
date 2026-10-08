package com.blog.social.api;

import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.AuthUser;
import com.blog.common.web.PageResponse;
import com.blog.social.domain.NotificationTab;
import com.blog.social.service.NotificationInboxService;
import com.blog.social.service.NotificationInboxService.Row;
import com.blog.social.service.NotificationInboxService.Settings;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림함과 알림 설정 (T111~T113, SOC-04). 안 읽은 수는 화면이 30초마다 {@code X-Auto-Request: true}로 부르므로 로그인 30분 연장으로
 * 세지 않는다 (ActivityPolicy, D-72).
 */
@RestController
public class NotificationController {

  private final NotificationInboxService inboxService;

  public NotificationController(NotificationInboxService inboxService) {
    this.inboxService = inboxService;
  }

  /**
   * @param keepDays 30 또는 7
   * @param settings 끌 수 있는 알림 종류 → 켜짐 여부. 보낸 것만 바꾼다
   */
  public record SettingsForm(Integer keepDays, Map<String, Boolean> settings) {}

  /**
   * @param tab ALL(기본), COMMENT, LIKE, FOLLOW(팔로우·구독), BLOG, OPERATION
   */
  @GetMapping("/api/notifications")
  public PageResponse<Row> list(
      @AuthenticationPrincipal AuthUser user,
      @RequestParam(defaultValue = "ALL") String tab,
      @RequestParam(defaultValue = "1") int page) {
    return inboxService.inbox(user.id(), tab(tab), page);
  }

  @GetMapping("/api/notifications/unread-count")
  public Map<String, Long> unreadCount(@AuthenticationPrincipal AuthUser user) {
    return Map.of("count", inboxService.unreadCount(user.id()));
  }

  @PostMapping("/api/notifications/{id}/read")
  public ResponseEntity<Void> read(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
    inboxService.markRead(user.id(), id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/notifications/read-all")
  public ResponseEntity<Void> readAll(@AuthenticationPrincipal AuthUser user) {
    inboxService.markAllRead(user.id());
    return ResponseEntity.noContent().build();
  }

  /** 전체 삭제. tab을 주면 그 탭만 */
  @DeleteMapping("/api/notifications")
  public Map<String, Integer> deleteAll(
      @AuthenticationPrincipal AuthUser user, @RequestParam(defaultValue = "ALL") String tab) {
    return Map.of("deleted", inboxService.deleteAll(user.id(), tab(tab)));
  }

  @GetMapping("/api/me/notification-settings")
  public Settings settings(@AuthenticationPrincipal AuthUser user) {
    return inboxService.settings(user.id());
  }

  @PutMapping("/api/me/notification-settings")
  public Settings updateSettings(
      @AuthenticationPrincipal AuthUser user, @RequestBody SettingsForm form) {
    return inboxService.update(user.id(), form.keepDays(), form.settings());
  }

  private static NotificationTab tab(String tab) {
    if ("ALL".equals(tab)) {
      return null;
    }
    try {
      return NotificationTab.valueOf(tab);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "알림 탭을 확인해 주세요.");
    }
  }
}
