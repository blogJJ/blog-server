package com.blog.blog.repository;

import com.blog.blog.domain.BlogMember;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlogMemberRepository extends JpaRepository<BlogMember, Long> {

  /** 블로그별 역할은 토큰이 아니라 요청마다 이 조회로 확인한다 (SEC-07). */
  Optional<BlogMember> findByBlogIdAndUserId(Long blogId, Long userId);

  boolean existsByBlogIdAndUserId(Long blogId, Long userId);

  /** 권한 확인용. 블로그·블로그장·회원을 같이 읽어 트랜잭션 밖(@PreAuthorize)에서도 쓸 수 있게 한다 (T017). */
  @Query(
      "select m from BlogMember m join fetch m.blog b join fetch b.owner join fetch m.user"
          + " where m.blog.id = :blogId and m.user.id = :userId")
  Optional<BlogMember> findForAuthz(@Param("blogId") Long blogId, @Param("userId") Long userId);

  /** 내 블로그 목록 (BLG-06). */
  List<BlogMember> findByUserId(Long userId);

  /** 내 블로그 목록 화면용. 블로그를 같이 읽는다. */
  @Query(
      "select m from BlogMember m join fetch m.blog b where m.user.id = :userId"
          + " order by m.joinedAt desc, m.id desc")
  List<BlogMember> findWithBlogByUserId(@Param("userId") Long userId);

  /** 부블로그장들. 참여 신청 알림을 보낼 때 쓴다. */
  @Query(
      "select m from BlogMember m join fetch m.user where m.blog.id = :blogId"
          + " and m.role = com.blog.blog.domain.BlogMemberRole.MANAGER")
  List<BlogMember> findManagers(@Param("blogId") Long blogId);
}
