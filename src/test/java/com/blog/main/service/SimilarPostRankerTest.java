package com.blog.main.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.main.service.SimilarPostRanker.Candidate;
import com.blog.main.service.SimilarPostRanker.Ranked;
import com.blog.main.service.SimilarPostRanker.Source;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 비슷한 글 순위 (T168). DB 없이 점수와 순서만 본다. */
class SimilarPostRankerTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);
  private static final int SAMPLE = 100;
  private static final long SRC_BLOG = 1L;

  private final Map<Long, Set<Long>> tags = new HashMap<>();
  private final Map<Long, Integer> freq = new HashMap<>();

  private static Source source(String title, Long... tagIds) {
    return new Source(1000L, SRC_BLOG, title, Set.of(tagIds));
  }

  private static Candidate post(long id, long blogId, String title) {
    return new Candidate(id, blogId, title, 0, 0, NOW.minusDays(30));
  }

  private List<Long> ids(List<Ranked> ranked) {
    return ranked.stream().map(r -> r.candidate().postId()).toList();
  }

  private List<Ranked> rank(Source src, List<Candidate> candidates, int max) {
    return SimilarPostRanker.rank(src, candidates, tags, freq, SAMPLE, NOW, max);
  }

  @Test
  void rareSharedTagBeatsCommonSharedTag() {
    freq.put(10L, 100); // 흔한 태그
    freq.put(20L, 2); // 드문 태그
    tags.put(1L, Set.of(10L));
    tags.put(2L, Set.of(20L));
    List<Ranked> ranked =
        rank(source("아무 제목", 10L, 20L), List.of(post(1, 2, "가나"), post(2, 3, "다라")), 4);
    assertThat(ids(ranked)).containsExactly(2L, 1L);
    assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
  }

  @Test
  void moreSharedTagsRankHigher() {
    freq.put(10L, 50);
    freq.put(11L, 50);
    tags.put(1L, Set.of(10L));
    tags.put(2L, Set.of(10L, 11L));
    assertThat(ids(rank(source("제목", 10L, 11L), List.of(post(1, 2, "가나"), post(2, 3, "다라")), 4)))
        .containsExactly(2L, 1L);
  }

  @Test
  void titleOverlapCountsWithoutTags() {
    List<Ranked> ranked =
        rank(
            source("제주도 여행 3일"),
            List.of(post(1, 2, "제주도 여행기"), post(2, 3, "고양이 사진"), post(3, 4, "부산 여행")),
            4);
    // 겹치는 글자가 많을수록 위, 전혀 안 겹치면 빠진다
    assertThat(ids(ranked)).containsExactly(1L, 3L);
  }

  @Test
  void freshnessAndPopularityNeverOutweighTopic() {
    freq.put(10L, 100);
    freq.put(11L, 100);
    tags.put(1L, Set.of(10L, 11L)); // 오래되고 조용한 글, 태그 둘
    tags.put(2L, Set.of(10L)); // 새롭고 인기 많은 글, 태그 하나
    Candidate old = new Candidate(1L, 2L, "가나", 0, 0, NOW.minusYears(3));
    Candidate hot = new Candidate(2L, 3L, "다라", 10_000, 1_000_000, NOW.minusHours(1));
    assertThat(ids(rank(source("제목", 10L, 11L), List.of(hot, old), 4))).containsExactly(1L, 2L);
    // 주제가 같으면 최신·인기로 가른다
    tags.put(1L, Set.of(10L));
    assertThat(ids(rank(source("제목", 10L, 11L), List.of(old, hot), 4))).containsExactly(2L, 1L);
  }

  @Test
  void noTopicalSignalMeansNoRecommendation() {
    Candidate popular = new Candidate(1L, 2L, "완전히 다른 글", 999, 99_999, NOW);
    assertThat(rank(source("제목"), List.of(popular), 4)).isEmpty();
    assertThat(rank(source("제목"), List.of(), 4)).isEmpty();
  }

  @Test
  void otherBlogsFirstSameBlogAsFiller() {
    freq.put(10L, 5);
    freq.put(11L, 5);
    tags.put(1L, Set.of(10L, 11L)); // 같은 블로그, 가장 비슷함
    tags.put(2L, Set.of(10L));
    tags.put(3L, Set.of(10L));
    List<Candidate> list = List.of(post(1, SRC_BLOG, "가"), post(2, 2, "나"), post(3, 3, "다"));
    assertThat(ids(rank(source("제목", 10L, 11L), list, 4))).containsExactly(3L, 2L, 1L);
    assertThat(ids(rank(source("제목", 10L, 11L), list, 2))).containsExactly(3L, 2L);
  }

  @Test
  void perBlogCapDefersExtrasBeforeSameBlog() {
    freq.put(10L, 5);
    for (long id = 1; id <= 4; id++) {
      tags.put(id, Set.of(10L));
    }
    tags.put(5L, Set.of(10L));
    // 블로그 2에 세 글, 블로그 3에 한 글, 같은 블로그에 한 글. 점수는 같고 번호가 큰 글이 먼저
    List<Candidate> list =
        List.of(
            post(4, 2, "가"),
            post(3, 2, "나"),
            post(2, 2, "다"),
            post(1, 3, "라"),
            post(5, SRC_BLOG, "마"));
    assertThat(ids(rank(source("제목", 10L), list, 8))).containsExactly(4L, 3L, 1L, 2L, 5L);
  }

  @Test
  void deterministicAndExcludesSourcePost() {
    freq.put(10L, 5);
    tags.put(1L, Set.of(10L));
    tags.put(2L, Set.of(10L));
    tags.put(1000L, Set.of(10L));
    List<Candidate> list = List.of(post(1, 2, "가"), post(1000, 2, "제목"), post(2, 3, "나"));
    List<Long> first = ids(rank(source("제목", 10L), list, 4));
    assertThat(first).containsExactly(2L, 1L);
    assertThat(ids(rank(source("제목", 10L), List.of(list.get(2), list.get(1), list.get(0)), 4)))
        .isEqualTo(first);
  }

  @Test
  void titleQueryIsSafeOrQuery() {
    assertThat(SimilarPostRanker.titleQuery("제주도와 바다 3일 A"))
        .isEqualTo("\"제주도와\" \"제주도\" \"바다\" \"3일\"");
    assertThat(SimilarPostRanker.titleQuery("\"><+-*()~@ 가")).isEmpty();
    assertThat(SimilarPostRanker.titleQuery(null)).isEmpty();
  }

  @Test
  void bigramDice() {
    assertThat(
            SimilarPostRanker.dice(
                SimilarPostRanker.bigrams("고양이 일기"), SimilarPostRanker.bigrams("고양이일기!")))
        .isEqualTo(1.0);
    assertThat(
            SimilarPostRanker.dice(
                SimilarPostRanker.bigrams("고양이"), SimilarPostRanker.bigrams("강아지")))
        .isZero();
  }
}
