package com.blog.blog.repository;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogVisibility;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlogRepository extends JpaRepository<Blog, Long> {

  Optional<Blog> findBySlug(String slug);

  Optional<Blog> findByShareKey(String shareKey);

  boolean existsBySlug(String slug);

  /** 생성 개수 제한용 (BLG-10). 폐쇄된 블로그는 세지 않는다. */
  @Query(
      "select count(b) from Blog b where b.owner.id = :ownerId and b.visibility in :visibilities"
          + " and b.status <> com.blog.blog.domain.BlogStatus.CLOSED and (:excludeId is null or b.id <> :excludeId)")
  long countOwned(
      @Param("ownerId") Long ownerId,
      @Param("visibilities") Collection<BlogVisibility> visibilities,
      @Param("excludeId") Long excludeId);

  /** 메인 블로그 목록 (BLG-02). 정렬은 Pageable로 준다. 공개·운영 중(폐쇄 예정 포함)·숨김 아님. */
  @Query(
      value =
          "select b from Blog b join fetch b.owner"
              + " where b.visibility = com.blog.blog.domain.BlogVisibility.PUBLIC"
              + " and b.status <> com.blog.blog.domain.BlogStatus.CLOSED and b.hidden = false",
      countQuery =
          "select count(b) from Blog b where b.visibility = com.blog.blog.domain.BlogVisibility.PUBLIC"
              + " and b.status <> com.blog.blog.domain.BlogStatus.CLOSED and b.hidden = false")
  Page<Blog> findListed(Pageable pageable);

  /**
   * 블로그 검색 (BLG-03). 이름·소개는 FULLTEXT ngram, 태그는 정리한 값과 정확히 같은 것. 관련도 순, 같으면 최신순. 공개 블로그만.
   *
   * @param tag 검색어를 태그 규칙으로 정리한 값. 태그로 쓸 수 없는 검색어면 빈 문자열
   */
  @Query(
      value =
          "select b.* from blogs b"
              + " where b.visibility = 'PUBLIC' and b.status <> 'CLOSED' and b.is_hidden = false"
              + " and (match(b.name, b.description) against (:q in boolean mode)"
              + " or exists (select 1 from blog_tags bt join tags t on t.id = bt.tag_id"
              + " where bt.blog_id = b.id and t.name = :tag))"
              + " order by match(b.name, b.description) against (:q in boolean mode) desc, b.id desc",
      countQuery =
          "select count(*) from blogs b"
              + " where b.visibility = 'PUBLIC' and b.status <> 'CLOSED' and b.is_hidden = false"
              + " and (match(b.name, b.description) against (:q in boolean mode)"
              + " or exists (select 1 from blog_tags bt join tags t on t.id = bt.tag_id"
              + " where bt.blog_id = b.id and t.name = :tag))",
      nativeQuery = true)
  Page<Blog> search(@Param("q") String q, @Param("tag") String tag, Pageable pageable);

  /** 멤버 수를 DB에서 바로 늘린다. 동시에 여러 명이 들어와도 빠지지 않는다. */
  @Modifying(flushAutomatically = true)
  @Query("update Blog b set b.memberCount = b.memberCount + 1 where b.id = :id")
  void increaseMemberCount(@Param("id") Long id);

  @Modifying(flushAutomatically = true)
  @Query(
      "update Blog b set b.memberCount = case when b.memberCount > 0 then b.memberCount - 1"
          + " else 0 end where b.id = :id")
  void decreaseMemberCount(@Param("id") Long id);

  /** 글 수를 DB에서 바로 바꾼다 (delta는 +1 또는 -1). 0 아래로는 내려가지 않는다. */
  @Modifying(flushAutomatically = true)
  @Query(
      "update Blog b set b.postCount = case when b.postCount + :delta < 0 then 0"
          + " else b.postCount + :delta end where b.id = :id")
  void addPostCount(@Param("id") Long id, @Param("delta") int delta);
}
