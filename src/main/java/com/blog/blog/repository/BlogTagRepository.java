package com.blog.blog.repository;

import com.blog.blog.domain.BlogTag;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlogTagRepository extends JpaRepository<BlogTag, Long> {

  /** 목록 화면에서 여러 블로그의 태그를 한 번에 읽는다. */
  @Query("select bt from BlogTag bt join fetch bt.tag where bt.blog.id in :blogIds order by bt.id")
  List<BlogTag> findWithTagByBlogIdIn(@Param("blogIds") Collection<Long> blogIds);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from BlogTag bt where bt.blog.id = :blogId")
  void deleteByBlogId(@Param("blogId") Long blogId);
}
