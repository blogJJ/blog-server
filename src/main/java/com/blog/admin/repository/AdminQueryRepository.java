package com.blog.admin.repository;

import com.blog.auth.domain.User;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogMember;
import com.blog.board.domain.Comment;
import com.blog.board.domain.Post;
import com.blog.social.domain.Report;
import com.blog.social.domain.ReportStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 관리자 화면에서만 쓰는 조회. 다른 모듈의 리포지토리를 늘리지 않으려고 여기에 모았다. 숨김·폐쇄·탈퇴처럼 일반 화면에서 빠지는 것도 모두 찾는다.
 *
 * <p>검색어 q는 서비스가 앞뒤 공백을 지우고 LIKE 특수 문자를 이스케이프해 {@code %q%}로 넘긴다. 빈 검색어면 null.
 */
public interface AdminQueryRepository extends Repository<User, Long> {

  /** 회원 검색: 이메일·닉네임·이름. 최신 가입순 */
  @Query(
      value =
          "select u from User u where :q is null or u.email like :q escape '!'"
              + " or u.nickname like :q escape '!' or u.name like :q escape '!' order by u.id desc",
      countQuery =
          "select count(u) from User u where :q is null or u.email like :q escape '!'"
              + " or u.nickname like :q escape '!' or u.name like :q escape '!'")
  Page<User> searchUsers(@Param("q") String q, Pageable pageable);

  /** 블로그 검색: 이름·주소. 숨김·폐쇄 포함, 최신순 */
  @Query(
      value =
          "select b from Blog b join fetch b.owner where :q is null or b.name like :q escape '!'"
              + " or b.slug like :q escape '!' order by b.id desc",
      countQuery =
          "select count(b) from Blog b where :q is null or b.name like :q escape '!'"
              + " or b.slug like :q escape '!'")
  Page<Blog> searchBlogs(@Param("q") String q, Pageable pageable);

  /** 글 검색: 제목. 숨김·삭제 포함, 최신순 */
  @Query(
      value =
          "select p from Post p join fetch p.blog join fetch p.author"
              + " where :q is null or p.title like :q escape '!' order by p.id desc",
      countQuery = "select count(p) from Post p where :q is null or p.title like :q escape '!'")
  Page<Post> searchPosts(@Param("q") String q, Pageable pageable);

  /** 한 글의 댓글 모두 (숨김·삭제 포함). 쓴 순서대로 */
  @Query("select c from Comment c join fetch c.author where c.post.id = :postId order by c.id asc")
  List<Comment> commentsOf(@Param("postId") Long postId);

  /** 메인 관리자가 처리할 신고(handler_scope = ADMIN). pending이면 대기 중만, 아니면 처리한 것만. 최신순 */
  @Query(
      value =
          "select r from Report r join fetch r.reporter left join fetch r.blog"
              + " where r.handlerScope = com.blog.social.domain.HandlerScope.ADMIN"
              + " and ((:pending = true and r.status = :pendingStatus)"
              + " or (:pending = false and r.status <> :pendingStatus))"
              + " order by r.id desc",
      countQuery =
          "select count(r) from Report r"
              + " where r.handlerScope = com.blog.social.domain.HandlerScope.ADMIN"
              + " and ((:pending = true and r.status = :pendingStatus)"
              + " or (:pending = false and r.status <> :pendingStatus))")
  Page<Report> adminReports(
      @Param("pending") boolean pending,
      @Param("pendingStatus") ReportStatus pendingStatus,
      Pageable pageable);

  /** 회원 상세의 블로그 목록. 폐쇄된 블로그 포함 */
  @Query(
      "select m from BlogMember m join fetch m.blog where m.user.id = :userId"
          + " order by m.joinedAt desc, m.id desc")
  List<BlogMember> membershipsOf(@Param("userId") Long userId);

  /** 이 사람이 블로그장인 폐쇄되지 않은 블로그들 (영구 정지면 모두 박탈, D-100) */
  @Query(
      "select b from Blog b where b.owner.id = :userId"
          + " and b.status <> com.blog.blog.domain.BlogStatus.CLOSED order by b.id asc")
  List<Blog> openBlogsOwnedBy(@Param("userId") Long userId);

  /** 블로그장 자리를 이어받을 부블로그장: 가장 먼저 부블로그장이 된 사람 (ADM-07, D-30) */
  @Query(
      "select m from BlogMember m join fetch m.user where m.blog.id = :blogId"
          + " and m.role = com.blog.blog.domain.BlogMemberRole.MANAGER"
          + " order by m.managerSince asc, m.id asc")
  List<BlogMember> managersByTenure(@Param("blogId") Long blogId);

  /** 공지 알림을 받을 회원: 탈퇴하지 않은 일반 회원 (BRD-10) */
  @Query(
      "select u.id from User u where u.status = com.blog.auth.domain.UserStatus.ACTIVE"
          + " and u.role = com.blog.auth.domain.UserRole.USER")
  List<Long> activeMemberIds();
}
