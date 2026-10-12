package com.blog.admin.repository;

import com.blog.admin.domain.OwnerSanction;
import com.blog.admin.domain.OwnerSanctionType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OwnerSanctionRepository extends JpaRepository<OwnerSanction, Long> {

  /** 그 블로그에서 이 사람이 after 뒤에 받은 경고 수 (D-97: 블로그별, 최근 1년) */
  long countByBlogIdAndUserIdAndTypeAndCreatedAtAfter(
      Long blogId, Long userId, OwnerSanctionType type, LocalDateTime after);

  /** 회원 상세의 블로그장 제재 이력. 최신순 */
  @Query(
      "select s from OwnerSanction s join fetch s.blog join fetch s.admin"
          + " where s.user.id = :userId order by s.id desc")
  List<OwnerSanction> findHistory(@Param("userId") Long userId);
}
