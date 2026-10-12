package com.blog.main.repository;

import com.blog.board.domain.Post;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 메인 피드·통합 검색의 글 조회 (T106, T107, BRD-08, BRD-09). 볼 때마다 모아온다 (D-73).
 *
 * <p>공통 조건: 게시 중인 글, 폐쇄·숨김이 아닌 블로그, {@code hidden}(보는 사람이 차단한 작성자, 없으면 BlockService.NONE)이 아닌 작성자.
 * 정렬은 Pageable의 Sort로 넘긴다(최신 = createdAt, 인기 = likeCount, D-89).
 */
public interface MainPostRepository extends Repository<Post, Long> {

  String LIVE =
      " p.status = com.blog.board.domain.PostStatus.PUBLISHED"
          + " and b.status <> com.blog.blog.domain.BlogStatus.CLOSED and b.hidden = false"
          + " and p.author.id not in :hidden";

  String PUBLIC_BLOG = " and b.visibility = com.blog.blog.domain.BlogVisibility.PUBLIC";

  /** 공개 블로그이거나 내가 멤버인 블로그. 비공개로 바뀐 블로그는 멤버가 아닌 구독자에게서 빠진다 (D-50). 메인 관리자는 모든 블로그의 글을 본다 (D-122). */
  String VISIBLE_TO_ME =
      " and (b.visibility = com.blog.blog.domain.BlogVisibility.PUBLIC"
          + " or exists (select m.id from BlogMember m where m.blog = b and m.user.id = :me)"
          + " or exists (select u.id from User u where u.id = :me"
          + " and u.role = com.blog.auth.domain.UserRole.ADMIN))";

  String FOLLOWING =
      " and p.authorHidden = false"
          + " and p.author.id in (select f.followee.id from UserFollow f where f.follower.id = :me)";

  String SUBSCRIBED =
      " and b.id in (select s.blog.id from BlogSubscription s where s.user.id = :me)";

  String TAGGED =
      " and exists (select pt.id from PostTag pt where pt.post = p and pt.tag.name = :tag)";

  /** 전체 최신·인기 탭: 공개 블로그의 글 */
  @Query(
      value =
          "select p from Post p join fetch p.blog b join fetch p.author where" + LIVE + PUBLIC_BLOG,
      countQuery = "select count(p) from Post p join p.blog b where" + LIVE + PUBLIC_BLOG)
  Page<Post> findPublic(@Param("hidden") Collection<Long> hidden, Pageable pageable);

  /** 팔로우 탭: 내가 팔로우한 회원의 글. 블로그를 떠나 "탈퇴한 계정"으로 보이는 글은 뺀다 */
  @Query(
      value =
          "select p from Post p join fetch p.blog b join fetch p.author where"
              + LIVE
              + VISIBLE_TO_ME
              + FOLLOWING,
      countQuery =
          "select count(p) from Post p join p.blog b where" + LIVE + VISIBLE_TO_ME + FOLLOWING)
  Page<Post> findFollowing(
      @Param("me") Long me, @Param("hidden") Collection<Long> hidden, Pageable pageable);

  /** 구독 탭: 내가 구독한 블로그의 글 */
  @Query(
      value =
          "select p from Post p join fetch p.blog b join fetch p.author where"
              + LIVE
              + VISIBLE_TO_ME
              + SUBSCRIBED,
      countQuery =
          "select count(p) from Post p join p.blog b where" + LIVE + VISIBLE_TO_ME + SUBSCRIBED)
  Page<Post> findSubscribed(
      @Param("me") Long me, @Param("hidden") Collection<Long> hidden, Pageable pageable);

  /** 태그 검색: 태그 규칙으로 정리한 값과 정확히 같은 태그가 달린 공개 블로그의 글 */
  @Query(
      value =
          "select p from Post p join fetch p.blog b join fetch p.author where"
              + LIVE
              + PUBLIC_BLOG
              + TAGGED,
      countQuery = "select count(p) from Post p join p.blog b where" + LIVE + PUBLIC_BLOG + TAGGED)
  Page<Post> findByTag(
      @Param("tag") String tag, @Param("hidden") Collection<Long> hidden, Pageable pageable);

  /** 닉네임 검색: 그 회원이 공개 블로그에 쓴 글. "탈퇴한 계정"으로 보이는 글은 뺀다 */
  @Query(
      value =
          "select p from Post p join fetch p.blog b join fetch p.author where"
              + LIVE
              + PUBLIC_BLOG
              + " and p.author.id = :authorId and p.authorHidden = false",
      countQuery =
          "select count(p) from Post p join p.blog b where"
              + LIVE
              + PUBLIC_BLOG
              + " and p.author.id = :authorId and p.authorHidden = false")
  Page<Post> findByAuthor(
      @Param("authorId") Long authorId,
      @Param("hidden") Collection<Long> hidden,
      Pageable pageable);

  /**
   * 글 제목 검색 (BRD-08). FULLTEXT ngram(ft_posts_title)이라 번호만 먼저 고르고, 내용은 {@link
   * #findWithBlogAndAuthor}로 읽는다.
   *
   * @param q BlogQueryService.toBooleanQuery로 만든 boolean mode 검색식
   * @param sort relevance(관련도, 같으면 최신), latest, popular(좋아요 수)
   */
  @Query(
      value =
          "select p.id from posts p join blogs b on b.id = p.blog_id"
              + " where p.status = 'PUBLISHED' and b.visibility = 'PUBLIC'"
              + " and b.status <> 'CLOSED' and b.is_hidden = false and p.author_id not in (:hidden)"
              + " and match(p.title) against (:q in boolean mode)"
              + " order by case when :sort = 'latest' then p.created_at end desc,"
              + " case when :sort = 'popular' then p.like_count end desc,"
              + " match(p.title) against (:q in boolean mode) desc, p.created_at desc, p.id desc",
      countQuery =
          "select count(*) from posts p join blogs b on b.id = p.blog_id"
              + " where p.status = 'PUBLISHED' and b.visibility = 'PUBLIC'"
              + " and b.status <> 'CLOSED' and b.is_hidden = false and p.author_id not in (:hidden)"
              + " and match(p.title) against (:q in boolean mode)",
      nativeQuery = true)
  Page<Long> searchTitleIds(
      @Param("q") String q,
      @Param("sort") String sort,
      @Param("hidden") Collection<Long> hidden,
      Pageable pageable);

  @Query("select p from Post p join fetch p.blog join fetch p.author where p.id in :ids")
  List<Post> findWithBlogAndAuthor(@Param("ids") Collection<Long> ids);
}
