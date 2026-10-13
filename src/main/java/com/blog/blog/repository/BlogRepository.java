package com.blog.blog.repository;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogVisibility;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
   * 블로그 검색 (BLG-03, BRD-08). 이름·소개는 FULLTEXT ngram, 태그는 정리한 값과 정확히 같은 것. 공개 블로그만.
   *
   * @param tag 검색어를 태그 규칙으로 정리한 값. 태그로 쓸 수 없는 검색어면 빈 문자열
   * @param sort relevance(관련도, 같으면 최신), latest(만든 순), popular(멤버 수, D-89)
   */
  @Query(
      value =
          "select b.* from blogs b"
              + " where b.visibility = 'PUBLIC' and b.status <> 'CLOSED' and b.is_hidden = false"
              + " and (match(b.name, b.description) against (:q in boolean mode)"
              + " or exists (select 1 from blog_tags bt join tags t on t.id = bt.tag_id"
              + " where bt.blog_id = b.id and t.name = :tag))"
              + " order by case when :sort = 'latest' then b.created_at end desc,"
              + " case when :sort = 'popular' then b.member_count end desc,"
              + " match(b.name, b.description) against (:q in boolean mode) desc, b.id desc",
      countQuery =
          "select count(*) from blogs b"
              + " where b.visibility = 'PUBLIC' and b.status <> 'CLOSED' and b.is_hidden = false"
              + " and (match(b.name, b.description) against (:q in boolean mode)"
              + " or exists (select 1 from blog_tags bt join tags t on t.id = bt.tag_id"
              + " where bt.blog_id = b.id and t.name = :tag))",
      nativeQuery = true)
  Page<Blog> search(
      @Param("q") String q,
      @Param("tag") String tag,
      @Param("sort") String sort,
      Pageable pageable);

  /** 구독자 수를 DB에서 바로 바꾼다 (delta는 +1 또는 -1). 0 아래로는 내려가지 않는다. */
  @Modifying(flushAutomatically = true)
  @Query(
      "update Blog b set b.subscriberCount = case when b.subscriberCount + :delta < 0 then 0"
          + " else b.subscriberCount + :delta end where b.id = :id")
  void addSubscriberCount(@Param("id") Long id, @Param("delta") int delta);

  /** 04:00 배치: 폐쇄 예정 시각이 지난 블로그 */
  @Query(
      "select b.id from Blog b where b.status = com.blog.blog.domain.BlogStatus.CLOSING"
          + " and b.closeScheduledAt <= :now order by b.id")
  List<Long> findDueToClose(@Param("now") LocalDateTime now);

  /** 04:00 배치: 아직 예정 시각 전인 폐쇄 예정 블로그 (3일 전·1일 전 알림) */
  @Query(
      "select b.id from Blog b where b.status = com.blog.blog.domain.BlogStatus.CLOSING"
          + " and b.closeScheduledAt > :now order by b.id")
  List<Long> findClosingAfter(@Param("now") LocalDateTime now);

  /** 회원 탈퇴 전 확인: 이 회원이 블로그장인 운영 중·폐쇄 예정 블로그 (USR-05, D-15, D-18) */
  @Query(
      "select b from Blog b where b.owner.id = :ownerId"
          + " and b.status <> com.blog.blog.domain.BlogStatus.CLOSED order by b.id")
  List<Blog> findOpenOwnedBy(@Param("ownerId") Long ownerId);

  /** 위임·폐쇄할 때 같은 블로그의 요청을 줄 세운다 */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from Blog b where b.id = :id")
  Optional<Blog> findByIdForUpdate(@Param("id") Long id);

  /**
   * 대표 이미지가 비어 있을 때만 Claude가 그린 그림을 넣는다 (T155, D-126). 그리는 동안 블로그장이 올린 이미지는 덮어쓰지 않는다.
   *
   * @return 넣었으면 1, 이미 이미지가 있거나 폐쇄됐으면 0
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update Blog b set b.coverImage = :cover where b.id = :id and b.coverImage is null"
          + " and b.status <> com.blog.blog.domain.BlogStatus.CLOSED")
  int fillEmptyCoverImage(@Param("id") Long id, @Param("cover") String cover);

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

  /** 마스코트나 픽셀 장면을 아직 안 만든 블로그 (BLG-14, BLG-15, D-116, D-119). 폐쇄된 블로그는 뺀다. */
  @Query(
      "select b.id from Blog b where (b.mascot is null or b.coverScene is null)"
          + " and b.status <> com.blog.blog.domain.BlogStatus.CLOSED order by b.id")
  List<Long> findIdsWithoutMascot(Pageable pageable);

  /**
   * 섞기 모드(일곱째 칸 {@code 배경~계절})가 아닌 예전 픽셀 장면을 가진 블로그 (T158, D-129 확장). 한 번 새로 고르면 {@code ~}가 붙어 다시
   * 나오지 않는다. 폐쇄된 블로그는 뺀다.
   */
  @Query(
      value =
          "select id from blogs where cover_scene is not null and cover_scene not like '%~%'"
              + " and status <> 'CLOSED' order by id limit :limit",
      nativeQuery = true)
  List<Long> findIdsWithLegacyCover(@Param("limit") int limit);

  /**
   * 운동 장면을 다시 볼 블로그 (T158 후속). 장면이 눈밭이거나 태그 이름이 운동 단어 정규식에 맞는 블로그를 번호 순서로 {@code afterId} 다음부터 읽는다.
   * 다시 고를지는 서비스가 태그로 정한다. 폐쇄된 블로그는 뺀다.
   */
  @Query(
      value =
          "select b.id from blogs b where b.id > :afterId and b.cover_scene is not null"
              + " and b.status <> 'CLOSED' and (b.cover_scene like 'snowfield.%'"
              + " or exists (select 1 from blog_tags bt join tags t on t.id = bt.tag_id"
              + " where bt.blog_id = b.id and t.name regexp :pattern))"
              + " order by b.id limit :limit",
      nativeQuery = true)
  List<Long> findIdsForSportRefresh(
      @Param("pattern") String pattern, @Param("afterId") long afterId, @Param("limit") int limit);

  /** 다른 블로그가 쓰는 같은 바탕 그림의 마스코트 값 (D-118). 폐쇄된 블로그는 뺀다. */
  @Query(
      value =
          "select mascot from blogs where mascot like concat(:base, '.%')"
              + " and id <> :blogId and status <> 'CLOSED'",
      nativeQuery = true)
  List<String> findMascotsInUse(@Param("base") String base, @Param("blogId") Long blogId);
}
