package com.blog.board.repository;

import com.blog.board.domain.Comment;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, Long> {

  /** 보이는 첫 댓글: 게시 중이거나, 자동 검사로 숨겨 확인을 기다리는 내 댓글이거나, 그런 답글이 남은 지운 댓글 (T166, D-133) */
  String ROOTS_WHERE =
      " where c.post.id = :postId and c.parent is null and ("
          + " (c.status = com.blog.board.domain.CommentStatus.ACTIVE"
          + " or (c.status = com.blog.board.domain.CommentStatus.HIDDEN"
          + " and c.reviewStatus = com.blog.board.domain.CommentReview.PENDING"
          + " and c.author.id = :viewerId))"
          + " or (c.status = com.blog.board.domain.CommentStatus.DELETED and exists ("
          + " select r.id from Comment r where r.parent = c and"
          + " (r.status = com.blog.board.domain.CommentStatus.ACTIVE"
          + " or (r.status = com.blog.board.domain.CommentStatus.HIDDEN"
          + " and r.reviewStatus = com.blog.board.domain.CommentReview.PENDING"
          + " and r.author.id = :viewerId)))))";

  /**
   * 글의 첫 댓글들, 오래된 순서. 지워졌어도 보이는 답글이 남아 있으면 "삭제된 댓글입니다" 자리로 함께 준다. 관리자가 숨긴 댓글은 뺀다. 자동 검사로 숨겨 확인을
   * 기다리는 댓글은 쓴 사람({@code viewerId})에게만 준다 (T166, D-133). 비회원은 viewerId가 null이다.
   */
  @Query(
      value =
          "select c from Comment c join fetch c.author"
              + ROOTS_WHERE
              + " order by c.createdAt asc, c.id asc",
      countQuery = "select count(c) from Comment c" + ROOTS_WHERE)
  Page<Comment> findRoots(
      @Param("postId") Long postId, @Param("viewerId") Long viewerId, Pageable pageable);

  /** 첫 댓글들에 달린 보이는 답글, 오래된 순서. 확인을 기다리는 답글은 쓴 사람에게만. */
  @Query(
      "select r from Comment r join fetch r.author left join fetch r.replyToUser"
          + " where r.parent.id in :parentIds and"
          + " (r.status = com.blog.board.domain.CommentStatus.ACTIVE"
          + " or (r.status = com.blog.board.domain.CommentStatus.HIDDEN"
          + " and r.reviewStatus = com.blog.board.domain.CommentReview.PENDING"
          + " and r.author.id = :viewerId))"
          + " order by r.createdAt asc, r.id asc")
  List<Comment> findReplies(
      @Param("parentIds") Collection<Long> parentIds, @Param("viewerId") Long viewerId);

  /** 블로그의 확인을 기다리는 댓글, 최근에 숨긴 순서 (T166, D-133) */
  @Query(
      "select c from Comment c join fetch c.author join fetch c.post p"
          + " where p.blog.id = :blogId"
          + " and c.status = com.blog.board.domain.CommentStatus.HIDDEN"
          + " and c.reviewStatus = com.blog.board.domain.CommentReview.PENDING"
          + " order by c.heldAt desc, c.id desc")
  List<Comment> findHeld(@Param("blogId") Long blogId);
}
