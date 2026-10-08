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

  @Modifying
  @Query("update Post p set p.viewCount = p.viewCount + 1 where p.id = :id")
  void increaseViewCount(@Param("id") Long id);
}
