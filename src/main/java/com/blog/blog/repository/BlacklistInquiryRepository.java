package com.blog.blog.repository;

import com.blog.blog.domain.BlacklistInquiry;
import com.blog.blog.domain.InquiryStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlacklistInquiryRepository extends JpaRepository<BlacklistInquiry, Long> {

  boolean existsByBlacklistIdAndUserIdAndStatus(
      Long blacklistId, Long userId, InquiryStatus status);

  /** 관리 화면의 문의 목록 */
  @Query(
      "select i from BlacklistInquiry i join fetch i.blacklist b join fetch i.user"
          + " where b.blog.id = :blogId and i.status = :status order by i.id desc")
  List<BlacklistInquiry> findForBlog(
      @Param("blogId") Long blogId, @Param("status") InquiryStatus status);
}
