package com.blog.main.service;

import com.blog.board.domain.Post;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.web.PageResponse;
import com.blog.main.repository.MainPostRepository;
import com.blog.main.service.PostCards.PostCard;
import com.blog.social.service.BlockService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메인 피드 (T106, BRD-09). 볼 때마다 모아오고(D-73), 팔로우와 구독은 탭을 나눈다(D-65).
 *
 * <ul>
 *   <li>latest·popular: 공개 블로그의 글. 인기는 좋아요 수 (D-89). 비회원도 본다.
 *   <li>following: 내가 팔로우한 회원의 글. subscribed: 내가 구독한 블로그의 글. 로그인해야 한다.
 *   <li>팔로우·구독 탭은 공개 블로그와 내가 멤버인 블로그의 글만 보인다. 비공개로 바뀐 블로그는 멤버가 아닌 구독자에게서 빠진다 (D-50). 일부 공개 블로그는 공유
 *       링크 없이는 글을 열 수 없어 멤버가 아니면 뺀다.
 *   <li>모든 탭에서 내가 차단한 회원의 글은 뺀다 (SOC-05).
 * </ul>
 */
@Service
public class FeedService {

  public static final List<String> TABS = List.of("latest", "popular", "following", "subscribed");

  private final MainPostRepository postRepository;
  private final BlockService blockService;
  private final PostCards cards;
  private final CountCache countCache;
  private final Clock clock;

  public FeedService(
      MainPostRepository postRepository,
      BlockService blockService,
      PostCards cards,
      CountCache countCache,
      Clock clock) {
    this.postRepository = postRepository;
    this.blockService = blockService;
    this.cards = cards;
    this.countCache = countCache;
    this.clock = clock;
  }

  /**
   * @param tab latest(기본)·popular·following·subscribed. 그 밖의 값은 latest
   * @param size 10·20·30, 그 밖이면 10
   */
  @Transactional(readOnly = true)
  public PageResponse<PostCard> feed(String tab, Long viewerId, int size, int page) {
    String t = TABS.contains(tab) ? tab : "latest";
    List<Long> hidden = blockService.hiddenAuthorIds(viewerId);
    int index = Math.max(page, 1) - 1;
    int pageSize = PostCards.pageSize(size);
    Page<Post> posts =
        switch (t) {
          case "popular" -> publicPosts(hidden, PageRequest.of(index, pageSize, PostCards.POPULAR));
          case "following" ->
              postRepository.findFollowing(
                  loggedIn(viewerId),
                  LocalDateTime.now(clock),
                  hidden,
                  PageRequest.of(index, pageSize, PostCards.LATEST));
          case "subscribed" ->
              postRepository.findSubscribed(
                  loggedIn(viewerId),
                  LocalDateTime.now(clock),
                  hidden,
                  PageRequest.of(index, pageSize, PostCards.LATEST));
          default -> publicPosts(hidden, PageRequest.of(index, pageSize, PostCards.LATEST));
        };
    return PageResponse.of(posts, cards::of);
  }

  /** 최신·인기 탭. 전체 개수는 차단 목록이 같으면 함께 쓴다 (T175) */
  private Page<Post> publicPosts(List<Long> hidden, PageRequest pageable) {
    return postRepository.withContent(
        countCache.page(
            postRepository.findPublicIds(hidden, pageable),
            pageable,
            "public:" + hidden,
            () -> postRepository.countPublic(hidden)));
  }

  private static Long loggedIn(Long viewerId) {
    if (viewerId == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "로그인하면 팔로우한 회원과 구독한 블로그의 새 글을 볼 수 있어요.");
    }
    return viewerId;
  }
}
