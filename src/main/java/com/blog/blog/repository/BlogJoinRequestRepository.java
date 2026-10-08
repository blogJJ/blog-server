package com.blog.blog.repository;

import com.blog.blog.domain.BlogJoinRequest;
import com.blog.blog.domain.JoinRequestStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlogJoinRequestRepository extends JpaRepository<BlogJoinRequest, Long> {

  /** 이 회원이 이 블로그에 낸 가장 최근 신청 (대기 중 재신청·거절 7일 확인) */
  Optional<BlogJoinRequest> findFirstByBlogIdAndUserIdOrderByIdDesc(Long blogId, Long userId);

  /** 블로그 관리 화면의 참여 신청 탭 */
  @Query(
      "select r from BlogJoinRequest r join fetch r.user"
          + " where r.blog.id = :blogId and r.status = :status order by r.createdAt, r.id")
  List<BlogJoinRequest> findWithUserByBlogIdAndStatus(
      @Param("blogId") Long blogId, @Param("status") JoinRequestStatus status);

  /** 내 블로그 목록의 "승인 대기" */
  @Query(
      "select r from BlogJoinRequest r join fetch r.blog"
          + " where r.user.id = :userId and r.status = 'PENDING' order by r.id desc")
  List<BlogJoinRequest> findPendingWithBlogByUserId(@Param("userId") Long userId);

  /** userId가 ownerId의 블로그들에 낸 대기 중 신청. 블로그장이 그 회원을 차단하면 거절한다 (SOC-05). */
  @Query(
      "select r from BlogJoinRequest r where r.user.id = :userId and r.blog.owner.id = :ownerId"
          + " and r.status = :status")
  List<BlogJoinRequest> findPendingToOwnerBlogs(
      @Param("userId") Long userId,
      @Param("ownerId") Long ownerId,
      @Param("status") JoinRequestStatus status);
}
