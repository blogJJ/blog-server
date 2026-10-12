package com.blog.blog.repository;

import com.blog.blog.domain.BlogCloseNotice;
import com.blog.blog.domain.CloseNoticeStage;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlogCloseNoticeRepository extends JpaRepository<BlogCloseNotice, Long> {

  boolean existsByBlogIdAndCloseScheduledAtAndStage(
      Long blogId, LocalDateTime closeScheduledAt, CloseNoticeStage stage);

  /**
   * 폐쇄 알림을 받을 회원: 블로그장(blogs.owner_id)을 뺀 멤버와 구독자, 탈퇴하지 않은 회원만 (D-14). 구독은 소셜 기능(US7)의 테이블이라 여기서는
   * 회원 번호만 읽는다.
   */
  @Query(
      value =
          "select m.user_id from blog_members m join users u on u.id = m.user_id"
              + " where m.blog_id = :blogId and u.status = 'ACTIVE'"
              + " and m.user_id <> (select b.owner_id from blogs b where b.id = :blogId)"
              + " union"
              + " select s.user_id from blog_subscriptions s join users u on u.id = s.user_id"
              + " where s.blog_id = :blogId and u.status = 'ACTIVE'"
              + " and s.user_id <> (select b.owner_id from blogs b where b.id = :blogId)",
      nativeQuery = true)
  List<Long> findReceiverIds(@Param("blogId") Long blogId);
}
