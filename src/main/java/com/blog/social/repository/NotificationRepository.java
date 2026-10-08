package com.blog.social.repository;

import com.blog.social.domain.Notification;
import com.blog.social.domain.NotificationTab;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

  long countByReceiverIdAndReadFalse(Long receiverId);

  /** 알림함 목록. tab이 null이면 전체 탭. 보관 기간(since) 안의 것만, 최신순 */
  @Query(
      value =
          "select n from Notification n left join fetch n.actor"
              + " where n.receiver.id = :receiverId and (:tab is null or n.tab = :tab)"
              + " and n.createdAt >= :since order by n.id desc",
      countQuery =
          "select count(n) from Notification n where n.receiver.id = :receiverId"
              + " and (:tab is null or n.tab = :tab) and n.createdAt >= :since")
  Page<Notification> findInbox(
      @Param("receiverId") Long receiverId,
      @Param("tab") NotificationTab tab,
      @Param("since") LocalDateTime since,
      Pageable pageable);

  @Query(
      "select count(n) from Notification n where n.receiver.id = :receiverId"
          + " and n.read = false and n.createdAt >= :since")
  long countUnread(@Param("receiverId") Long receiverId, @Param("since") LocalDateTime since);

  Optional<Notification> findByIdAndReceiverId(Long id, Long receiverId);

  @Modifying
  @Query(
      "update Notification n set n.read = true where n.receiver.id = :receiverId and n.read = false")
  int markAllRead(@Param("receiverId") Long receiverId);

  /** 전체 삭제 (SOC-04). tab이 null이면 모든 탭 */
  @Modifying
  @Query(
      "delete from Notification n where n.receiver.id = :receiverId and (:tab is null or n.tab = :tab)")
  int deleteInbox(@Param("receiverId") Long receiverId, @Param("tab") NotificationTab tab);
}
