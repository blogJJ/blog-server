package com.blog.auth.repository;

import com.blog.auth.domain.RefreshToken;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  @EntityGraph(attributePaths = "user")
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  @EntityGraph(attributePaths = "user")
  Optional<RefreshToken> findWithUserById(Long id);

  /** "로그인 유지"를 안 한 로그인의 끝나는 시각을 늘린다 (30분 연장, ActivityPolicy). */
  @Modifying
  @Query(
      "update RefreshToken r set r.expiresAt = :until"
          + " where r.id = :id and r.rememberMe = false and r.revokedAt is null"
          + " and r.expiresAt > :now and r.expiresAt < :until")
  int extend(
      @Param("id") Long id, @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

  /** 그 회원의 살아 있는 로그인을 모두 폐기한다 (비밀번호 변경·탈퇴 때) */
  @Modifying
  @Query(
      "update RefreshToken r set r.revokedAt = :now where r.user.id = :userId and r.revokedAt is null")
  int revokeAllOf(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
