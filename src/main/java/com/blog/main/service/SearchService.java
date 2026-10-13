package com.blog.main.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.service.BlogQueryService;
import com.blog.board.domain.Post;
import com.blog.board.service.ImageService;
import com.blog.board.service.TagNormalizer;
import com.blog.common.error.BusinessException;
import com.blog.common.web.PageResponse;
import com.blog.main.repository.MainPostRepository;
import com.blog.social.service.BlockService;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 통합 검색 (T107, BRD-08). 분류바로 대상(블로그·글 제목·태그·작성자 닉네임)을 하나 고른다. 자동완성은 없다.
 *
 * <ul>
 *   <li>검색어는 앞뒤 공백을 뗀 2~20자, 글자나 숫자가 하나는 있어야 한다 (BlogQueryService.validateQuery).
 *   <li>블로그(이름·소개)와 글 제목은 FULLTEXT ngram, 태그와 닉네임은 정확히 같은 값만.
 *   <li>정렬은 관련도(기본)·최신·인기. 블로그 인기는 멤버 수, 글 인기는 좋아요 수 (D-89). 태그·닉네임은 모두 똑같이 맞는 결과라 관련도는 최신순과 같다.
 *   <li>비공개·일부 공개 블로그, 숨김·삭제 글, 내가 차단한 회원의 글은 나오지 않는다.
 *   <li>로그인한 회원은 검색어가 최근 검색어에 남는다 (T108).
 * </ul>
 */
@Service
public class SearchService {

  public static final List<String> TYPES = List.of("blog", "post", "tag", "nickname");
  public static final String NO_RESULT = "검색 결과가 없습니다";

  /**
   * 검색 결과. items는 type이 blog면 BlogSummary, 그 밖에는 PostCard.
   *
   * @param user 닉네임 검색에서 찾은 회원 (프로필 링크용). 없거나 내가 차단한 회원이면 null
   * @param message 결과가 없으면 "검색 결과가 없습니다", 있으면 null
   */
  public record SearchResult(
      String type,
      String q,
      String sort,
      List<?> items,
      int page,
      int totalPages,
      long totalElements,
      UserHit user,
      String message) {

    static SearchResult of(String type, String q, String sort, PageResponse<?> page, UserHit user) {
      boolean empty = page.totalElements() == 0 && user == null;
      return new SearchResult(
          type,
          q,
          sort,
          page.items(),
          page.page(),
          page.totalPages(),
          page.totalElements(),
          user,
          empty ? NO_RESULT : null);
    }
  }

  /** 닉네임 검색에서 찾은 회원. 이메일·이름·전화번호는 주지 않는다. */
  public record UserHit(Long id, String nickname, String profileImage, String bio) {}

  private final MainPostRepository postRepository;
  private final BlogQueryService blogQueryService;
  private final UserRepository userRepository;
  private final BlockService blockService;
  private final RecentSearchService recentSearchService;
  private final PostCards cards;

  public SearchService(
      MainPostRepository postRepository,
      BlogQueryService blogQueryService,
      UserRepository userRepository,
      BlockService blockService,
      RecentSearchService recentSearchService,
      PostCards cards) {
    this.postRepository = postRepository;
    this.blogQueryService = blogQueryService;
    this.userRepository = userRepository;
    this.blockService = blockService;
    this.recentSearchService = recentSearchService;
    this.cards = cards;
  }

  /**
   * @param type blog·post(기본)·tag·nickname. 그 밖의 값은 post
   * @param sort relevance(기본)·latest·popular. 그 밖의 값은 relevance
   * @param size 글 결과 한 페이지 개수 10·20·30 (블로그는 목록과 같은 12개)
   */
  @Transactional
  public SearchResult search(
      String rawQuery, String type, String sort, Long viewerId, int size, int page) {
    String q = BlogQueryService.validateQuery(rawQuery);
    String t = TYPES.contains(type) ? type : "post";
    String s = BlogQueryService.normalizeSort(sort);
    if (viewerId != null) {
      recentSearchService.record(viewerId, q);
    }
    List<Long> hidden = blockService.hiddenAuthorIds(viewerId);
    Pageable pageable = PageRequest.of(Math.max(page, 1) - 1, PostCards.pageSize(size));
    return switch (t) {
      case "blog" -> SearchResult.of(t, q, s, blogQueryService.search(q, s, page), null);
      case "tag" -> SearchResult.of(t, q, s, byTag(q, s, hidden, pageable), null);
      case "nickname" -> byNickname(q, s, hidden, pageable);
      default -> SearchResult.of(t, q, s, byTitle(q, s, hidden, pageable), null);
    };
  }

  private PageResponse<PostCards.PostCard> byTitle(
      String q, String sort, List<Long> hidden, Pageable pageable) {
    String fulltext = BlogQueryService.toBooleanQuery(q);
    if (fulltext.isEmpty()) {
      return PageResponse.of(Page.<Post>empty(pageable), cards::of);
    }
    return PageResponse.of(
        postRepository.withContent(postRepository.searchTitleIds(fulltext, sort, hidden, pageable)),
        cards::of);
  }

  private PageResponse<PostCards.PostCard> byTag(
      String q, String sort, List<Long> hidden, Pageable pageable) {
    String tag;
    try {
      tag = TagNormalizer.normalize(q);
    } catch (BusinessException e) {
      tag = null; // 태그로 쓸 수 없는 검색어는 맞는 태그가 없다
    }
    if (tag == null) {
      return PageResponse.of(Page.<Post>empty(pageable), cards::of);
    }
    return PageResponse.of(
        postRepository.findByTag(tag, hidden, sorted(pageable, sort)), cards::of);
  }

  private SearchResult byNickname(String q, String sort, List<Long> hidden, Pageable pageable) {
    User user =
        userRepository
            .findByNickname(q)
            .filter(User::isActive)
            .filter(u -> !hidden.contains(u.getId()))
            .orElse(null);
    if (user == null) {
      return SearchResult.of(
          "nickname", q, sort, PageResponse.of(Page.<Post>empty(pageable), cards::of), null);
    }
    PageResponse<PostCards.PostCard> posts =
        PageResponse.of(
            postRepository.findByAuthor(user.getId(), hidden, sorted(pageable, sort)), cards::of);
    UserHit hit =
        new UserHit(
            user.getId(),
            user.getNickname(),
            ImageService.url(user.getProfileImage()),
            user.getBio());
    return SearchResult.of("nickname", q, sort, posts, hit);
  }

  /** 일치 검색은 관련도가 모두 같아 relevance도 최신순 */
  private static Pageable sorted(Pageable pageable, String sort) {
    return PageRequest.of(
        pageable.getPageNumber(),
        pageable.getPageSize(),
        "popular".equals(sort) ? PostCards.POPULAR : PostCards.LATEST);
  }
}
