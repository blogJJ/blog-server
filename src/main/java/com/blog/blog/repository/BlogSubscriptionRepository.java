package com.blog.blog.repository;

import com.blog.blog.domain.BlogSubscription;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlogSubscriptionRepository extends JpaRepository<BlogSubscription, Long> {

  boolean existsByBlogIdAndUserId(Long blogId, Long userId);

  @Modifying
  @Query(
      value = "insert ignore into blog_subscriptions (blog_id, user_id) values (:blogId, :userId)",
      nativeQuery = true)
  int insertIgnore(@Param("blogId") Long blogId, @Param("userId") Long userId);

  @Modifying
  @Query("delete from BlogSubscription s where s.blog.id = :blogId and s.user.id = :userId")
  int deletePair(@Param("blogId") Long blogId, @Param("userId") Long userId);

  /** 구독자들. 비공개로 바뀌면 모두에게 알린다 (BLG-01, 3.6 알림 종류 "비공개 전환"). */
  @Query("select s.user.id from BlogSubscription s where s.blog.id = :blogId")
  List<Long> findSubscriberIds(@Param("blogId") Long blogId);
}
