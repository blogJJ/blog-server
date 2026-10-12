package com.blog.blog.repository;

import com.blog.blog.domain.BlogOwnerTransfer;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlogOwnerTransferRepository extends JpaRepository<BlogOwnerTransfer, Long> {

  /** 블로그의 대기 중인 요청. 블로그마다 한 번에 하나만 둔다 */
  @Query(
      "select t from BlogOwnerTransfer t join fetch t.toUser where t.blog.id = :blogId"
          + " and t.status = com.blog.blog.domain.TransferStatus.PENDING order by t.id desc")
  List<BlogOwnerTransfer> findPendingByBlogId(@Param("blogId") Long blogId);

  /** 내가 받은 대기 중인 요청 (위임 수락 화면) */
  @Query(
      "select t from BlogOwnerTransfer t join fetch t.blog join fetch t.fromUser"
          + " where t.toUser.id = :userId"
          + " and t.status = com.blog.blog.domain.TransferStatus.PENDING order by t.id desc")
  List<BlogOwnerTransfer> findPendingForReceiver(@Param("userId") Long userId);

  /** 이 회원이 보냈거나 받은 대기 중인 요청 (블로그 탈퇴·회원 탈퇴 때 취소) */
  @Query(
      "select t from BlogOwnerTransfer t join fetch t.blog"
          + " where (t.toUser.id = :userId or t.fromUser.id = :userId)"
          + " and t.status = com.blog.blog.domain.TransferStatus.PENDING")
  List<BlogOwnerTransfer> findPendingInvolving(@Param("userId") Long userId);

  /** 04:00 배치: 7일 지난 대기 요청 */
  @Query(
      "select t from BlogOwnerTransfer t join fetch t.blog where t.status ="
          + " com.blog.blog.domain.TransferStatus.PENDING and t.expiresAt <= :now order by t.id")
  List<BlogOwnerTransfer> findExpired(@Param("now") LocalDateTime now);

  @Query("select t.blog.id from BlogOwnerTransfer t where t.id = :id")
  Optional<Long> findBlogIdById(@Param("id") Long id);

  /** 수락·거절할 때 같은 요청을 두 번 처리하지 않게 잠근다 */
  @Query("select t from BlogOwnerTransfer t where t.id = :id")
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<BlogOwnerTransfer> findByIdForUpdate(@Param("id") Long id);
}
