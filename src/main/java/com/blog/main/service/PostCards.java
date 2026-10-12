package com.blog.main.service;

import com.blog.board.domain.Post;
import com.blog.board.repository.PostImageRepository;
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
   * @param authorId 프로필로 가는 링크용. "탈퇴한 계정"이면 null
   * @param thumbnail 글의 첫 이미지 주소. 없으면 null
   */
  public record PostCard(
      Long id,
      String title,
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

  private final PostImageRepository imageRepository;

  public PostCards(PostImageRepository imageRepository) {
    this.imageRepository = imageRepository;
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

  /** 10·20·30개 중 하나, 그 밖이면 10 (D-06) */
  public static int pageSize(int size) {
    return PostQueryService.PAGE_SIZES.contains(size) ? size : 10;
  }
}
