package com.blog.social.repository;

import com.blog.social.domain.NotificationSetting;
import com.blog.social.domain.NotificationType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {

  Optional<NotificationSetting> findByUserIdAndType(Long userId, NotificationType type);

  List<NotificationSetting> findByUserId(Long userId);

  /** userIds 중 이 종류를 끈 회원 번호 */
  @Query(
      "select s.user.id from NotificationSetting s"
          + " where s.type = :type and s.enabled = false and s.user.id in :userIds")
  List<Long> findDisabledUserIds(
      @Param("type") NotificationType type, @Param("userIds") Collection<Long> userIds);
}
