package com.blog.board.repository;

import com.blog.board.domain.Post;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

  /** 상세 화면용. 블로그·작성자·카테고리를 같이 읽는다. */
  @Query(
      "select p from Post p join fetch p.blog b join fetch b.owner join fetch p.author"
          + " left join fetch p.category where p.id = :id")
  Optional<Post> findDetail(@Param("id") Long id);

  /** 블로그 글 목록 (BRD-02). 공지는 따로 보여 주므로 뺀다. categoryId가 null이면 전체. 최신순. */
  @Query(
      value =
          "select p from Post p join fetch p.author left join fetch p.category"
              + " where p.blog.id = :blogId and p.status = com.blog.board.domain.PostStatus.PUBLISHED"
              + " and p.notice = false and (:categoryId is null or p.category.id = :categoryId)"
              + " order by p.createdAt desc, p.id desc",
      countQuery =
          "select count(p) from Post p"
              + " where p.blog.id = :blogId and p.status = com.blog.board.domain.PostStatus.PUBLISHED"
              + " and p.notice = false and (:categoryId is null or p.category.id = :categoryId)")
  Page<Post> findPublished(
      @Param("blogId") Long blogId, @Param("categoryId") Long categoryId, Pageable pageable);

  /** 블로그 공지 (최신 몇 개) */
  @Query(
      "select p from Post p join fetch p.author left join fetch p.category"
          + " where p.blog.id = :blogId and p.status = com.blog.board.domain.PostStatus.PUBLISHED"
          + " and p.notice = true order by p.createdAt desc, p.id desc")
  List<Post> findNotices(@Param("blogId") Long blogId, Pageable pageable);

  // 숫자만 바꾸는 update는 updated_at을 그대로 적어 MySQL ON UPDATE가 "수정됨" 시각을 바꾸지 않게 한다

  @Modifying
  @Query(
      "update Post p set p.viewCount = p.viewCount + 1, p.updatedAt = p.updatedAt where p.id = :id")
  void increaseViewCount(@Param("id") Long id);

  @Modifying
  @Query(
      "update Post p set p.commentCount = p.commentCount + :delta, p.updatedAt = p.updatedAt"
          + " where p.id = :id")
  void addCommentCount(@Param("id") Long id, @Param("delta") int delta);

  @Modifying
  @Query(
      "update Post p set p.likeCount = p.likeCount + :delta, p.updatedAt = p.updatedAt"
          + " where p.id = :id")
  void addLikeCount(@Param("id") Long id, @Param("delta") int delta);

  /** 카테고리를 지우면 그 카테고리 글은 "카테고리 없음"이 된다. */
  @Modifying
  @Query("update Post p set p.category = null where p.category.id = :categoryId")
  int clearCategory(@Param("categoryId") Long categoryId);

  /** 강제 퇴장·탈퇴한 작성자의 그 블로그 글을 "탈퇴한 계정"으로 (BLG-11) */
  @Modifying
  @Query(
      "update Post p set p.authorHidden = true, p.updatedAt = p.updatedAt"
          + " where p.blog.id = :blogId and p.author.id = :authorId")
  int hideAuthor(@Param("blogId") Long blogId, @Param("authorId") Long authorId);
}
