package com.blog.main.service;

import com.blog.blog.service.BlogAccessService;
import com.blog.board.domain.Post;
import com.blog.board.repository.PostImageRepository;
import com.blog.board.repository.PostRepository;
import com.blog.board.service.ContentRenderer;
import com.blog.board.service.ImageService;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.main.repository.SimilarPostRepository;
import com.blog.main.repository.SimilarPostRepository.Row;
import com.blog.main.repository.SimilarPostRepository.TagHit;
import com.blog.main.service.SimilarPostRanker.Candidate;
import com.blog.main.service.SimilarPostRanker.Ranked;
import com.blog.main.service.SimilarPostRanker.Source;
import com.blog.social.service.BlockService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 글 아래 "이런 글은 어때요" (T168). 같은 주제의 다른 블로그 글을 보여 줘 더 오래 머물게 한다.
 *
 * <ul>
 *   <li>원래 글은 글 상세와 같은 규칙(BlogAccessService)으로 볼 수 있어야 한다. 못 보면 404·403.
 *   <li>추천하는 글은 비회원도 볼 수 있는 글만(공개 블로그, 게시 중, 블로그 공지 아님, 폐쇄·숨김 아님, 같은 글 아님). 다른 블로그 글을 먼저, 같은 블로그 글은
 *       채우기용. 순위는 {@link SimilarPostRanker}.
 *   <li>보는 사람이 차단한 작성자의 글은 뺀다 (SOC-05).
 *   <li>글 화면마다 부르므로 AI를 부르지 않고, 글마다 계산한 결과를 메모리에 잠깐({@code app.similar-posts.cache-ttl}, 기본 5분) 둔다.
 *       캐시에는 비회원 기준 결과만 두고 차단은 꺼낼 때 거른다.
 * </ul>
 */
@Service
public class SimilarPostService {

  public static final int DEFAULT_LIMIT = 4;
  public static final int MAX_LIMIT = 8;

  /** 캐시에 두는 개수. 차단한 작성자를 빼도 MAX_LIMIT을 채울 수 있게 넉넉히. */
  static final int KEEP = 16;

  /** 태그마다 읽는 최근 글 수(표본 크기). */
  static final int PER_TAG = 100;

  /** 제목으로 찾는 후보 수. */
  static final int BY_TITLE = 30;

  static final int EXCERPT_CHARS = 60;

  /** 캐시 최대 글 수. 넘으면 오래된 것부터 비운다. */
  static final int CACHE_MAX = 5_000;

  /**
   * 추천 카드.
   *
   * @param thumbnail 글의 첫 이미지 → 블로그 대표 이미지. 둘 다 없으면 null(화면이 마스코트를 그린다)
   * @param blogMascot 블로그 마스코트 조합(bear.mint.beret.heart 꼴). 없으면 null
   */
  public record SimilarPost(
      Long id,
      String title,
      String excerpt,
      String thumbnail,
      Long blogId,
      String blogSlug,
      String blogName,
      String blogMascot,
      LocalDateTime createdAt) {}

  private record Entry(SimilarPost card, Long authorId) {}

  private record Cached(List<Entry> entries, LocalDateTime expiresAt) {}

  private final PostRepository postRepository;
  private final SimilarPostRepository similarRepository;
  private final PostImageRepository imageRepository;
  private final BlogAccessService accessService;
  private final BlockService blockService;
  private final ContentRenderer renderer;
  private final Clock clock;
  private final Duration ttl;
  private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

  public SimilarPostService(
      PostRepository postRepository,
      SimilarPostRepository similarRepository,
      PostImageRepository imageRepository,
      BlogAccessService accessService,
      BlockService blockService,
      ContentRenderer renderer,
      Clock clock,
      @Value("${app.similar-posts.cache-ttl:5m}") Duration ttl) {
    this.postRepository = postRepository;
    this.similarRepository = similarRepository;
    this.imageRepository = imageRepository;
    this.accessService = accessService;
    this.blockService = blockService;
    this.renderer = renderer;
    this.clock = clock;
    this.ttl = ttl;
  }

  /**
   * @param viewerId 로그인한 회원. 비회원이면 null
   * @param shareKey 원래 글이 일부 공개 블로그면 공유 링크 값
   * @param limit 1~{@link #MAX_LIMIT}, 밖이면 맞춘다
   */
  @Transactional(readOnly = true)
  public List<SimilarPost> similar(Long postId, Long viewerId, String shareKey, int limit) {
    Post post =
        postRepository
            .findDetail(postId)
            .filter(Post::isPublished)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    accessService.check(post.getBlog(), viewerId, shareKey);

    int n = Math.clamp(limit, 1, MAX_LIMIT);
    Set<Long> hidden = new HashSet<>(blockService.hiddenAuthorIds(viewerId));
    return entries(post).stream()
        .filter(e -> !hidden.contains(e.authorId()))
        .limit(n)
        .map(Entry::card)
        .toList();
  }

  private List<Entry> entries(Post post) {
    LocalDateTime now = LocalDateTime.now(clock);
    Cached hit = cache.get(post.getId());
    if (hit != null && hit.expiresAt().isAfter(now)) {
      return hit.entries();
    }
    List<Entry> computed = compute(post, now);
    if (!ttl.isZero() && !ttl.isNegative()) {
      if (cache.size() >= CACHE_MAX) {
        cache.values().removeIf(c -> !c.expiresAt().isAfter(now));
        if (cache.size() >= CACHE_MAX) {
          cache.clear();
        }
      }
      cache.put(post.getId(), new Cached(computed, now.plus(ttl)));
    }
    return computed;
  }

  private List<Entry> compute(Post post, LocalDateTime now) {
    Long postId = post.getId();
    List<Long> tagIds = similarRepository.tagIdsOf(postId);

    Map<Long, Set<Long>> tagsByPost = new HashMap<>();
    Map<Long, Integer> tagFrequency = new HashMap<>();
    for (TagHit hit : similarRepository.postsByTags(tagIds, postId, PER_TAG)) {
      tagsByPost.computeIfAbsent(hit.postId(), k -> new HashSet<>()).add(hit.tagId());
      tagFrequency.merge(hit.tagId(), 1, Integer::sum);
    }
    Set<Long> ids = new LinkedHashSet<>(tagsByPost.keySet());
    String q = SimilarPostRanker.titleQuery(post.getTitle());
    if (!q.isEmpty()) {
      ids.addAll(similarRepository.postsByTitle(q, postId, BY_TITLE));
    }
    if (ids.isEmpty()) {
      return List.of();
    }

    Map<Long, Row> rows =
        similarRepository.rows(ids).stream().collect(Collectors.toMap(Row::id, r -> r));
    List<Candidate> candidates =
        rows.values().stream()
            .map(
                r ->
                    new Candidate(
                        r.id(), r.blogId(), r.title(), r.likeCount(), r.viewCount(), r.createdAt()))
            .toList();
    List<Ranked> ranked =
        SimilarPostRanker.rank(
            new Source(postId, post.getBlog().getId(), post.getTitle(), new HashSet<>(tagIds)),
            candidates,
            tagsByPost,
            tagFrequency,
            PER_TAG,
            now,
            KEEP);
    if (ranked.isEmpty()) {
      return List.of();
    }

    List<Long> chosen = ranked.stream().map(r -> r.candidate().postId()).toList();
    Map<Long, String> contents = similarRepository.contents(chosen);
    Map<Long, String> thumbs =
        imageRepository.findFirstImages(chosen).stream()
            .collect(
                Collectors.toMap(
                    i -> i.getPost().getId(),
                    i -> ImageService.url(i.getStoredName()),
                    (a, b) -> a));
    List<Entry> entries = new ArrayList<>(chosen.size());
    for (Long id : chosen) {
      Row r = rows.get(id);
      String thumbnail = thumbs.getOrDefault(id, ImageService.url(r.blogCover()));
      entries.add(
          new Entry(
              new SimilarPost(
                  r.id(),
                  r.title(),
                  excerpt(r.title(), contents.get(id)),
                  thumbnail,
                  r.blogId(),
                  r.blogSlug(),
                  r.blogName(),
                  r.blogMascot(),
                  r.createdAt()),
              r.authorId()));
    }
    return List.copyOf(entries);
  }

  /** 본문 앞 60자. 본문이 제목(# 제목)으로 시작하면 카드에 제목이 두 번 나오지 않게 뗀다. */
  private String excerpt(String title, String markdown) {
    String text = renderer.excerpt(markdown, EXCERPT_CHARS + Post.TITLE_MAX + 1);
    boolean cut = text.endsWith("…");
    if (cut) {
      text = text.substring(0, text.length() - 1);
    }
    if (title != null && text.startsWith(title)) {
      text = text.substring(title.length()).strip();
    }
    if (text.codePointCount(0, text.length()) <= EXCERPT_CHARS) {
      return cut ? text + "…" : text;
    }
    return text.substring(0, text.offsetByCodePoints(0, EXCERPT_CHARS)) + "…";
  }
}
