package com.blog.main.service;

import com.blog.board.domain.Post;
import com.blog.board.repository.PostImageRepository;
import com.blog.board.service.ContentRenderer;
import com.blog.board.service.ImageService;
import com.blog.board.service.PostQueryService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** 메인 피드·통합 검색이 같이 쓰는 글 카드 (T106, T107). 글이 어느 블로그에서 왔는지 같이 보여 준다 (D-65). */
@Component
public class PostCards {

  public static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  /** 인기 = 좋아요 수 (D-89). 같으면 최신 */
  public static final Sort POPULAR =
      Sort.by(Sort.Order.desc("likeCount"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  /**
   * 글 카드 한 장.
   *
   * @param excerpt 본문 앞부분을 글자만 (목록 미리보기, D-149)
   * @param authorId 프로필로 가는 링크용. "탈퇴한 계정"이면 null
   * @param thumbnail 글의 첫 이미지 주소. 없으면 null
   */
  public record PostCard(
      Long id,
      String title,
      String excerpt,
      Long authorId,
      String authorNickname,
      Long blogId,
      String blogSlug,
      String blogName,
      int viewCount,
      int likeCount,
      int commentCount,
      String thumbnail,
      LocalDateTime createdAt) {}

  /** 목록 미리보기 글자 수 */
  static final int EXCERPT_CHARS = 100;

  private final PostImageRepository imageRepository;
  private final ContentRenderer renderer;

  public PostCards(PostImageRepository imageRepository, ContentRenderer renderer) {
    this.imageRepository = imageRepository;
    this.renderer = renderer;
  }

  /** 블로그와 작성자를 이미 읽은 글들을 카드로 바꾼다. 순서는 그대로. */
  public List<PostCard> of(List<Post> posts) {
    if (posts.isEmpty()) {
      return List.of();
    }
    Map<Long, String> thumbs =
        imageRepository.findFirstImages(posts.stream().map(Post::getId).toList()).stream()
            .collect(
                Collectors.toMap(
                    i -> i.getPost().getId(),
                    i -> ImageService.url(i.getStoredName()),
                    (a, b) -> a));
    List<PostCard> cards = new ArrayList<>(posts.size());
    for (Post p : posts) {
      cards.add(
          new PostCard(
              p.getId(),
              p.getTitle(),
              excerpt(p),
              PostQueryService.authorId(p),
              PostQueryService.authorName(p),
              p.getBlog().getId(),
              p.getBlog().getSlug(),
              p.getBlog().getName(),
              p.getViewCount(),
              p.getLikeCount(),
              p.getCommentCount(),
              thumbs.get(p.getId()),
              p.getCreatedAt()));
    }
    return cards;
  }

  /** 본문 글자만 앞 100자. 본문이 제목으로 시작하면(첫 줄을 제목으로 다시 쓴 글) 그 제목은 빼고 센다 */
  private String excerpt(Post post) {
    String text = renderer.excerpt(post.getContent(), Integer.MAX_VALUE);
    String title = post.getTitle();
    if (title != null && !title.isBlank() && text.startsWith(title)) {
      text = text.substring(title.length()).strip();
    }
    if (text.codePointCount(0, text.length()) <= EXCERPT_CHARS) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, EXCERPT_CHARS)) + "…";
  }

  /** 10·20·30개 중 하나, 그 밖이면 10 (D-06) */
  public static int pageSize(int size) {
    return PostQueryService.PAGE_SIZES.contains(size) ? size : 10;
  }
}
