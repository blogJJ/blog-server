package com.blog.social.repository;

import com.blog.social.domain.UserBlock;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

  boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

  /** 둘 중 한 명이라도 상대를 차단했으면 true */
  @Query(
      "select count(b) > 0 from UserBlock b where (b.blocker.id = :a and b.blocked.id = :b)"
          + " or (b.blocker.id = :b and b.blocked.id = :a)")
  boolean existsBetween(@Param("a") Long a, @Param("b") Long b);

  /** 내가 차단한 회원 번호들. 목록·피드·검색에서 뺄 때 쓴다. */
  @Query("select b.blocked.id from UserBlock b where b.blocker.id = :blockerId")
  List<Long> findBlockedIds(@Param("blockerId") Long blockerId);

  /** receivers 중 actor를 차단한 회원들. 그 회원에게는 actor가 일으킨 알림을 만들지 않는다. */
  @Query(
      "select b.blocker.id from UserBlock b where b.blocked.id = :actorId"
          + " and b.blocker.id in :receiverIds")
  List<Long> findBlockersOf(
      @Param("actorId") Long actorId, @Param("receiverIds") Collection<Long> receiverIds);

  /** 내 차단 목록. 최근에 차단한 순서. */
  @Query(
      "select b from UserBlock b join fetch b.blocked where b.blocker.id = :blockerId"
          + " order by b.id desc")
  List<UserBlock> findWithBlockedByBlockerId(@Param("blockerId") Long blockerId);

  @Modifying
  @Query(
      value = "insert ignore into user_blocks (blocker_id, blocked_id) values (:blockerId, :blockedId)",
      nativeQuery = true)
  int insertIgnore(@Param("blockerId") Long blockerId, @Param("blockedId") Long blockedId);

  @Modifying
  @Query("delete from UserBlock b where b.blocker.id = :blockerId and b.blocked.id = :blockedId")
  int deletePair(@Param("blockerId") Long blockerId, @Param("blockedId") Long blockedId);
}
