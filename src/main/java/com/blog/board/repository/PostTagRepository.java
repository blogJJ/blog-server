package com.blog.board.repository;

import com.blog.board.domain.PostTag;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostTagRepository extends JpaRepository<PostTag, Long> {

  @Query("select pt from PostTag pt join fetch pt.tag where pt.post.id in :postIds order by pt.id")
  List<PostTag> findWithTagByPostIdIn(@Param("postIds") Collection<Long> postIds);

  @Modifying(flushAutomatically = true)
  @Query("delete from PostTag pt where pt.post.id = :postId")
  void deleteByPostId(@Param("postId") Long postId);
}
