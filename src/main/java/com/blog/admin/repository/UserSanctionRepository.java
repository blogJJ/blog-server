package com.blog.admin.repository;

import com.blog.admin.domain.UserSanction;
import com.blog.admin.domain.UserSanctionType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSanctionRepository extends JpaRepository<UserSanction, Long> {

  /** 정지 해제 때 마지막 정지 기록에 해제 시각을 남긴다 */
  Optional<UserSanction> findFirstByUserIdAndTypeOrderByIdDesc(Long userId, UserSanctionType type);

  /** 회원 상세의 계정 제재 이력. 최신순 */
  @Query(
      "select s from UserSanction s join fetch s.admin where s.user.id = :userId order by s.id desc")
  List<UserSanction> findHistory(@Param("userId") Long userId);
}
