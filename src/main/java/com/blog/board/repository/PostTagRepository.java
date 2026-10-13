package com.blog.board.repository;

import com.blog.board.domain.PostTag;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostTagRepository extends JpaRepository<PostTag, Long> {

  @Query("select pt from PostTag pt join fetch pt.tag where pt.post.id in :postIds order by pt.id")
  List<PostTag> findWithTagByPostIdIn(@Param("postIds") Collection<Long> postIds);

  /** 이 블로그 글에 달린 태그 이름을 많이 쓴 순으로. 글쓰기 도우미의 태그 추천이 쓴다 (T167). */
  @Query(
      "select t.name from PostTag pt join pt.tag t join pt.post p where p.blog.id = :blogId"
          + " and p.status = com.blog.board.domain.PostStatus.PUBLISHED"
          + " group by t.name order by count(pt) desc, t.name")
  List<String> findTagNamesByBlogId(@Param("blogId") Long blogId, Pageable pageable);

  @Modifying(flushAutomatically = true)
  @Query("delete from PostTag pt where pt.post.id = :postId")
  void deleteByPostId(@Param("postId") Long postId);
}
