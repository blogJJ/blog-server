package com.blog.social.repository;

import com.blog.social.domain.UserFollow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserFollowRepository extends JpaRepository<UserFollow, Long> {

  boolean existsByFollowerIdAndFolloweeId(Long followerId, Long followeeId);

  /** 탈퇴하지 않은 팔로워 수 */
  @Query(
      "select count(f) from UserFollow f where f.followee.id = :userId"
          + " and f.follower.status = com.blog.auth.domain.UserStatus.ACTIVE")
  long countFollowers(@Param("userId") Long userId);

  /** 동시에 두 번 눌러도 한 행만 생기도록 INSERT IGNORE. 새로 넣었으면 1. */
  @Modifying
  @Query(
      value =
          "insert ignore into user_follows (follower_id, followee_id) values (:followerId, :followeeId)",
      nativeQuery = true)
  int insertIgnore(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);

  /** 지웠으면 1. */
  @Modifying
  @Query("delete from UserFollow f where f.follower.id = :followerId and f.followee.id = :followeeId")
  int deletePair(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);

  /** 차단할 때 서로의 팔로우를 푼다 (SOC-05). */
  @Modifying
  @Query(
      "delete from UserFollow f where (f.follower.id = :a and f.followee.id = :b)"
          + " or (f.follower.id = :b and f.followee.id = :a)")
  int deleteBetween(@Param("a") Long a, @Param("b") Long b);

  /** 이 회원을 팔로우하는 사람들. 최근에 팔로우한 순서. */
  @Query(
      value =
          "select f from UserFollow f join fetch f.follower u where f.followee.id = :userId"
              + " and u.status = com.blog.auth.domain.UserStatus.ACTIVE order by f.id desc",
      countQuery =
          "select count(f) from UserFollow f where f.followee.id = :userId"
              + " and f.follower.status = com.blog.auth.domain.UserStatus.ACTIVE")
  Page<UserFollow> findFollowers(@Param("userId") Long userId, Pageable pageable);

  /** 이 회원이 팔로우하는 사람들. 최근에 팔로우한 순서. */
  @Query(
      value =
          "select f from UserFollow f join fetch f.followee u where f.follower.id = :userId"
              + " and u.status = com.blog.auth.domain.UserStatus.ACTIVE order by f.id desc",
      countQuery =
          "select count(f) from UserFollow f where f.follower.id = :userId"
              + " and f.followee.status = com.blog.auth.domain.UserStatus.ACTIVE")
  Page<UserFollow> findFollowings(@Param("userId") Long userId, Pageable pageable);
}
