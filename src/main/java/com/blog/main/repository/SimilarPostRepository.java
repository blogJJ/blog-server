package com.blog.main.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 비슷한 글 후보 조회 (T168). 비회원이 볼 수 있는 글만 고른다: 게시 중이고 블로그 공지가 아닌 글, 공개 블로그, 폐쇄·숨김이 아닌 블로그. 메인 공지
 * 블로그(main)는 비공개라 저절로 빠진다.
 *
 * <p>쿼리는 모두 기존 인덱스로 끝난다: 태그는 {@code idx_post_tags_tag (tag_id, post_id)}를 뒤에서부터 읽어 태그마다 최근 글 몇 개에서
 * 멈추고, 제목은 {@code ft_posts_title}(ngram), 나머지는 기본 키로 읽는다.
 */
@Repository
public class SimilarPostRepository {

  /** 비회원 기준으로 볼 수 있는 글. posts p, blogs b로 쓴다. */
  static final String VISIBLE =
      " p.status = 'PUBLISHED' and p.is_notice = false and b.visibility = 'PUBLIC'"
          + " and b.status <> 'CLOSED' and b.is_hidden = false";

  /** 점수를 매길 때 쓰는 글 정보. 본문은 무거워서 따로 읽는다. */
  public record Row(
      Long id,
      Long blogId,
      Long authorId,
      String title,
      int likeCount,
      int viewCount,
      LocalDateTime createdAt,
      String blogSlug,
      String blogName,
      String blogCover,
      String blogMascot) {}

  /** 태그로 찾은 글 한 건. */
  public record TagHit(Long tagId, Long postId) {}

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public SimilarPostRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
  }

  /** 글에 달린 태그 번호. {@code uk_post_tags (post_id, tag_id)}만 읽는다. */
  public List<Long> tagIdsOf(Long postId) {
    return jdbc.queryForList(
        "select tag_id from post_tags where post_id = ? order by tag_id", Long.class, postId);
  }

  /**
   * 태그마다 그 태그가 달린 최근 글(번호가 큰 순)을 {@code perTag}개까지. 태그가 아주 흔해도 태그당 읽는 양이 정해져 있다. 한 번에 보내려고 태그마다 괄호로
   * 묶은 쿼리를 UNION ALL로 잇는다.
   *
   * <p>STRAIGHT_JOIN으로 post_tags부터 읽게 한다. 그러면 {@code idx_post_tags_tag}를 뒤에서부터 읽으며(정렬 없음) 글·블로그는 기본
   * 키로 한 행씩 확인하고, 조건에 맞는 글이 {@code perTag}개가 되면 멈춘다. 그냥 두면 데이터가 적을 때 옵티마이저가 공개 블로그의 글을 모두 훑고 정렬하는
   * 계획을 고른다.
   */
  public List<TagHit> postsByTags(Collection<Long> tagIds, Long excludePostId, int perTag) {
    if (tagIds.isEmpty()) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder();
    List<Object> args = new ArrayList<>();
    for (Long tagId : tagIds) {
      if (!sql.isEmpty()) {
        sql.append(" union all ");
      }
      sql.append("(select straight_join pt.tag_id, pt.post_id from post_tags pt")
          .append(" join posts p on p.id = pt.post_id join blogs b on b.id = p.blog_id")
          .append(" where pt.tag_id = ? and pt.post_id <> ? and")
          .append(VISIBLE)
          .append(" order by pt.post_id desc limit ?)");
      args.add(tagId);
      args.add(excludePostId);
      args.add(perTag);
    }
    return jdbc.query(
        sql.toString(),
        (rs, i) -> new TagHit(rs.getLong("tag_id"), rs.getLong("post_id")),
        args.toArray());
  }

  /**
   * 제목이 비슷한 글 번호, 관련도 순. {@code q}는 boolean mode 검색식(낱말을 OR로 이은 것)이다.
   *
   * @param q 비어 있으면 부르지 않는다
   */
  public List<Long> postsByTitle(String q, Long excludePostId, int max) {
    return jdbc.queryForList(
        "select p.id from posts p join blogs b on b.id = p.blog_id"
            + " where match(p.title) against (? in boolean mode) and p.id <> ? and"
            + VISIBLE
            + " order by match(p.title) against (? in boolean mode) desc, p.id desc limit ?",
        Long.class,
        q,
        excludePostId,
        q,
        max);
  }

  /** 후보의 점수용 정보. 조건을 한 번 더 걸어 그사이 숨겨진 글은 빠진다. */
  public List<Row> rows(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return named.query(
        "select p.id, p.blog_id, p.author_id, p.title, p.like_count, p.view_count, p.created_at,"
            + " b.slug, b.name, b.cover_image, b.mascot"
            + " from posts p join blogs b on b.id = p.blog_id where p.id in (:ids) and"
            + VISIBLE,
        new MapSqlParameterSource("ids", ids),
        (rs, i) -> {
          Timestamp created = rs.getTimestamp("created_at");
          return new Row(
              rs.getLong("id"),
              rs.getLong("blog_id"),
              rs.getLong("author_id"),
              rs.getString("title"),
              rs.getInt("like_count"),
              rs.getInt("view_count"),
              created == null ? null : created.toLocalDateTime(),
              rs.getString("slug"),
              rs.getString("name"),
              rs.getString("cover_image"),
              rs.getString("mascot"));
        });
  }

  /** 고른 글의 본문 마크다운(발췌용). */
  public Map<Long, String> contents(Collection<Long> ids) {
    Map<Long, String> map = new HashMap<>();
    if (ids.isEmpty()) {
      return map;
    }
    named.query(
        "select id, content from posts where id in (:ids)",
        new MapSqlParameterSource("ids", ids),
        (RowCallbackHandler) rs -> map.put(rs.getLong("id"), rs.getString("content")));
    return map;
  }
}
