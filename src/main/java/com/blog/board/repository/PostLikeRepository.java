package com.blog.board.repository;

import com.blog.board.domain.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

  boolean existsByPostIdAndUserId(Long postId, Long userId);

  /** 동시에 두 번 눌러도 한 행만 생기도록 INSERT IGNORE. 새로 넣었으면 1. */
  @Modifying
  @Query(
      value = "insert ignore into post_likes (post_id, user_id) values (:postId, :userId)",
      nativeQuery = true)
  int insertIgnore(@Param("postId") Long postId, @Param("userId") Long userId);

  /** 지웠으면 1. */
  @Modifying
  @Query("delete from PostLike l where l.post.id = :postId and l.user.id = :userId")
  int deleteByPostIdAndUserId(@Param("postId") Long postId, @Param("userId") Long userId);
}
