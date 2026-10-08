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

  /** 글의 첫 댓글들, 오래된 순서. 지워졌어도 보이는 답글이 남아 있으면 "삭제된 댓글입니다" 자리로 함께 준다. 관리자가 숨긴 댓글은 뺀다. */
  @Query(
      value =
          "select c from Comment c join fetch c.author where c.post.id = :postId and c.parent is null"
              + " and (c.status = com.blog.board.domain.CommentStatus.ACTIVE"
              + " or (c.status = com.blog.board.domain.CommentStatus.DELETED and exists ("
              + " select r.id from Comment r where r.parent = c"
              + " and r.status = com.blog.board.domain.CommentStatus.ACTIVE)))"
              + " order by c.createdAt asc, c.id asc",
      countQuery =
          "select count(c) from Comment c where c.post.id = :postId and c.parent is null"
              + " and (c.status = com.blog.board.domain.CommentStatus.ACTIVE"
              + " or (c.status = com.blog.board.domain.CommentStatus.DELETED and exists ("
              + " select r.id from Comment r where r.parent = c"
              + " and r.status = com.blog.board.domain.CommentStatus.ACTIVE)))")
  Page<Comment> findRoots(@Param("postId") Long postId, Pageable pageable);

  /** 첫 댓글들에 달린 보이는 답글, 오래된 순서. */
  @Query(
      "select r from Comment r join fetch r.author left join fetch r.replyToUser"
          + " where r.parent.id in :parentIds"
          + " and r.status = com.blog.board.domain.CommentStatus.ACTIVE"
          + " order by r.createdAt asc, r.id asc")
  List<Comment> findReplies(@Param("parentIds") Collection<Long> parentIds);
}
