package com.blog.blog.repository;

import com.blog.blog.domain.MemberSanction;
import com.blog.blog.domain.SanctionType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberSanctionRepository extends JpaRepository<MemberSanction, Long> {

  /** 가장 최근 제재. 정지 안내에 사유를 보여 줄 때 쓴다 */
  Optional<MemberSanction> findFirstByBlogIdAndUserIdAndTypeOrderByIdDesc(
      Long blogId, Long userId, SanctionType type);

  /** 멤버 관리 화면의 이력, 최신순 */
  @Query(
      "select s from MemberSanction s join fetch s.issuedBy where s.blog.id = :blogId"
          + " and s.user.id = :userId order by s.id desc")
  List<MemberSanction> findHistory(@Param("blogId") Long blogId, @Param("userId") Long userId);
}
