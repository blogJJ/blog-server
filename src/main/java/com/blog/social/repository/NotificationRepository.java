package com.blog.social.repository;

import com.blog.social.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

  long countByReceiverIdAndReadFalse(Long receiverId);
}
