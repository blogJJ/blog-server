package com.blog.main.repository;

import com.blog.board.domain.Post;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 메인 공지 읽기 (BRD-10). 공지는 main 블로그의 게시 중인 공지 글이다. */
public interface NoticeRepository extends Repository<Post, Long> {

  @Query(
      value =
          "select p from Post p where p.blog.slug = :slug and p.notice = true"
              + " and p.status = com.blog.board.domain.PostStatus.PUBLISHED order by p.id desc",
      countQuery =
          "select count(p) from Post p where p.blog.slug = :slug and p.notice = true"
              + " and p.status = com.blog.board.domain.PostStatus.PUBLISHED")
  Page<Post> findNotices(@Param("slug") String slug, Pageable pageable);

  @Query(
      "select p from Post p where p.id = :id and p.blog.slug = :slug and p.notice = true"
          + " and p.status = com.blog.board.domain.PostStatus.PUBLISHED")
  Optional<Post> findNotice(@Param("slug") String slug, @Param("id") Long id);
}
