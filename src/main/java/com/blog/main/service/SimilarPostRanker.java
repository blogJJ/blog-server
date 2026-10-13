package com.blog.main.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 비슷한 글 순위 (T168). AI를 부르지 않고 같은 입력이면 늘 같은 결과를 낸다.
 *
 * <p>점수 = 주제 점수(겹치는 태그 + 제목 비슷함) + 최신 가산 + 인기 가산.
 *
 * <ul>
 *   <li>태그: 겹치는 태그마다 {@code 1 + ln(표본 크기 / 그 태그가 달린 글 수) / 2}. 드문 태그가 겹칠수록 더 비슷하다고 본다. 흔한 태그 하나는
 *       1점, 글이 하나뿐인 태그는 약 3.6점.
 *   <li>제목: 띄어쓰기·기호를 뺀 두 글자 묶음(bigram)의 Dice 계수 × 2 (0~2점). 한국어 조사가 붙어도 대부분 겹친다.
 *   <li>주제 점수가 {@link #MIN_TOPICAL}보다 작으면 뺀다. 최신·인기만으로는 추천하지 않는다.
 *   <li>최신 가산: {@code 0.3 × e^(-지난 날 / 180)}. 인기 가산: {@code 0.3 × ln(1 + 좋아요 + 조회/20) / ln(101)},
 *       최대 0.3. 둘을 합쳐도 흔한 태그 하나(1점)보다 작아서 주제가 먼저다.
 *   <li>순서: 다른 블로그 글을 먼저, 한 블로그에서 {@link #PER_BLOG}개까지. 넘친 다른 블로그 글을 그다음, 같은 블로그 글은 마지막에 채운다. 점수가
 *       같으면 최근 글(번호가 큰 글)이 먼저.
 * </ul>
 */
public final class SimilarPostRanker {

  public static final double MIN_TOPICAL = 0.3;
  public static final int PER_BLOG = 2;
  static final double FRESH_WEIGHT = 0.3;
  static final double FRESH_DAYS = 180;
  static final double POPULAR_WEIGHT = 0.3;
  static final double TITLE_WEIGHT = 2.0;

  private SimilarPostRanker() {}

  /** 지금 보고 있는 글. */
  public record Source(Long postId, Long blogId, String title, Set<Long> tagIds) {}

  /** 후보 글. */
  public record Candidate(
      Long postId,
      Long blogId,
      String title,
      int likeCount,
      int viewCount,
      LocalDateTime createdAt) {}

  /** 순위가 매겨진 후보. */
  public record Ranked(Candidate candidate, double score) {}

  /**
   * @param tagsByPost 후보마다 원래 글과 겹치는 태그 번호
   * @param tagFrequency 태그마다 표본에서 그 태그가 달린 글 수
   * @param sampleSize 태그마다 읽은 최대 글 수(표본 크기)
   * @param max 돌려줄 최대 개수
   */
  public static List<Ranked> rank(
      Source source,
      List<Candidate> candidates,
      Map<Long, Set<Long>> tagsByPost,
      Map<Long, Integer> tagFrequency,
      int sampleSize,
      LocalDateTime now,
      int max) {
    Set<String> sourceGrams = bigrams(source.title());
    List<Ranked> scored = new ArrayList<>();
    for (Candidate c : candidates) {
      if (c.postId().equals(source.postId())) {
        continue;
      }
      double topical =
          tagScore(tagsByPost.getOrDefault(c.postId(), Set.of()), tagFrequency, sampleSize)
              + TITLE_WEIGHT * dice(sourceGrams, bigrams(c.title()));
      if (topical < MIN_TOPICAL) {
        continue;
      }
      scored.add(new Ranked(c, topical + freshness(c.createdAt(), now) + popularity(c)));
    }
    scored.sort(
        Comparator.comparingDouble(Ranked::score)
            .reversed()
            .thenComparing(r -> r.candidate().postId(), Comparator.reverseOrder()));

    List<Ranked> result = new ArrayList<>();
    List<Ranked> overflow = new ArrayList<>();
    List<Ranked> sameBlog = new ArrayList<>();
    Map<Long, Integer> perBlog = new HashMap<>();
    for (Ranked r : scored) {
      Long blogId = r.candidate().blogId();
      if (blogId.equals(source.blogId())) {
        sameBlog.add(r);
      } else if (perBlog.merge(blogId, 1, Integer::sum) > PER_BLOG) {
        overflow.add(r);
      } else {
        result.add(r);
      }
    }
    result.addAll(overflow);
    result.addAll(sameBlog);
    return result.size() > max ? List.copyOf(result.subList(0, max)) : result;
  }

  static double tagScore(Set<Long> shared, Map<Long, Integer> tagFrequency, int sampleSize) {
    double score = 0;
    for (Long tag : shared) {
      int df = Math.max(1, Math.min(sampleSize, tagFrequency.getOrDefault(tag, sampleSize)));
      score += 1 + Math.log((double) sampleSize / df) / 2;
    }
    return score;
  }

  static double freshness(LocalDateTime createdAt, LocalDateTime now) {
    if (createdAt == null) {
      return 0;
    }
    double days = Math.max(0, Duration.between(createdAt, now).toHours() / 24.0);
    return FRESH_WEIGHT * Math.exp(-days / FRESH_DAYS);
  }

  static double popularity(Candidate c) {
    double raw = Math.max(0, c.likeCount()) + Math.max(0, c.viewCount()) / 20.0;
    return POPULAR_WEIGHT * Math.min(1, Math.log1p(raw) / Math.log(101));
  }

  /** 띄어쓰기·기호를 빼고 소문자로 바꾼 두 글자 묶음. 한 글자뿐이면 그 글자 하나. */
  static Set<String> bigrams(String title) {
    String s =
        title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    Set<String> grams = new HashSet<>();
    int[] cps = s.codePoints().toArray();
    if (cps.length == 1) {
      grams.add(s);
    }
    for (int i = 0; i + 1 < cps.length; i++) {
      grams.add(new String(cps, i, 2));
    }
    return grams;
  }

  static double dice(Set<String> a, Set<String> b) {
    if (a.isEmpty() || b.isEmpty()) {
      return 0;
    }
    int common = 0;
    for (String g : a) {
      if (b.contains(g)) {
        common++;
      }
    }
    return 2.0 * common / (a.size() + b.size());
  }

  /**
   * 제목으로 후보를 찾을 boolean mode 검색식. 두 글자 이상 낱말을 따옴표로 묶어 OR로 잇고, 세 글자 이상이면 끝 한 글자(조사일 때가 많다)를 뗀 것도
   * 넣는다. 글자·숫자만 남기므로 검색식 문법을 깨지 않는다. 낱말이 없으면 빈 문자열.
   */
  public static String titleQuery(String title) {
    Set<String> terms = new LinkedHashSet<>();
    for (String word : (title == null ? "" : title).split("\\s+")) {
      String w = word.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
      int len = w.codePointCount(0, w.length());
      if (len >= 2) {
        terms.add(w);
      }
      if (len >= 3) {
        terms.add(w.substring(0, w.offsetByCodePoints(0, len - 1)));
      }
    }
    StringBuilder sb = new StringBuilder();
    for (String t : terms) {
      if (!sb.isEmpty()) {
        sb.append(' ');
      }
      sb.append('"').append(t).append('"');
    }
    return sb.toString();
  }
}
