package com.blog.admin.repository;

import com.blog.admin.domain.AdminActionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long> {

  /** 활동 기록 화면. 최신순 */
  @Query(
      value = "select l from AdminActionLog l join fetch l.admin order by l.id desc",
      countQuery = "select count(l) from AdminActionLog l")
  Page<AdminActionLog> findLatest(Pageable pageable);
}
