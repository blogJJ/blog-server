package com.blog.social.repository;

import com.blog.social.domain.HandlerScope;
import com.blog.social.domain.Report;
import com.blog.social.domain.ReportStatus;
import com.blog.social.domain.ReportTargetType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportRepository extends JpaRepository<Report, Long> {

  /** 같은 사람이 같은 대상을 2주 안에 신고했는지 (SOC-06) */
  boolean existsByReporterIdAndTargetTypeAndTargetIdAndCreatedAtAfter(
      Long reporterId, ReportTargetType targetType, Long targetId, LocalDateTime after);

  /** 블로그장이 보는 신고 목록. 대기 중인 것 먼저, 최신순 */
  @Query(
      "select r from Report r join fetch r.reporter where r.blog.id = :blogId"
          + " and r.handlerScope = :scope and r.status = :status order by r.createdAt desc, r.id desc")
  List<Report> findForBlog(
      @Param("blogId") Long blogId,
      @Param("scope") HandlerScope scope,
      @Param("status") ReportStatus status);
}
