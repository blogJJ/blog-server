package com.blog.board.service;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.ManagerPermission;
import com.blog.blog.service.BlogAccessService;
import com.blog.board.domain.Category;
import com.blog.board.domain.Post;
import com.blog.board.repository.CategoryRepository;
import com.blog.board.repository.PostImageRepository;
import com.blog.board.repository.PostLikeRepository;
import com.blog.board.repository.PostRepository;
import com.blog.board.repository.PostTagRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.BlogAuthz;
import com.blog.common.web.PageResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 글 읽기: 블로그 글 목록·공지·글 상세·카테고리 목록 (T064, T065, BRD-02, BRD-03, D-76). 열람 권한은 BlogAccessService가 정한다.
 */
@Service
public class PostQueryService {

  public static final Set<Integer> PAGE_SIZES = Set.of(10, 20, 30);
  public static final int NOTICE_LIMIT = 5;
  public static final String HIDDEN_AUTHOR = "탈퇴한 계정";

  /**
   * 목록 한 줄.
   *
   * @param thumbnail 글의 첫 이미지 주소. 제목에 마우스를 올리면 보여 준다 (BRD-05)
   */
  public record PostSummary(
      Long id,
      String title,
      String authorNickname,
      String categoryName,
      boolean notice,
      int viewCount,
      int likeCount,
      int commentCount,
      String thumbnail,
      LocalDateTime createdAt) {}

  public record PostList(List<PostSummary> notices, PageResponse<PostSummary> page) {}

  /**
   * 글 상세.
   *
   * @param html 걸러낸 본문 HTML. 화면은 이것만 innerHTML로 넣는다
   * @param markdown 고칠 때 쓰는 원문. 작성자에게만 준다
   * @param liked 보는 회원이 좋아요를 눌렀는지
   */
  public record PostDetail(
      Long id,
      String title,
      String html,
      String markdown,
      String authorNickname,
      Long categoryId,
      String categoryName,
      boolean notice,
      List<String> tags,
      int viewCount,
      int likeCount,
      int commentCount,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      BlogRef blog,
      boolean canEdit,
      boolean canDelete,
      boolean liked) {}

  public record BlogRef(Long id, String slug, String name, String coverImage) {}

  public record CategoryRow(Long id, String name, int sortOrder) {}

  private final PostRepository postRepository;
  private final PostTagRepository postTagRepository;
  private final PostImageRepository imageRepository;
  private final CategoryRepository categoryRepository;
  private final PostLikeRepository likeRepository;
  private final BlogAccessService accessService;
  private final BlogAuthz blogAuthz;
  private final ContentRenderer renderer;

  public PostQueryService(
      PostRepository postRepository,
      PostTagRepository postTagRepository,
      PostImageRepository imageRepository,
      CategoryRepository categoryRepository,
      PostLikeRepository likeRepository,
      BlogAccessService accessService,
      BlogAuthz blogAuthz,
      ContentRenderer renderer) {
    this.postRepository = postRepository;
    this.postTagRepository = postTagRepository;
    this.imageRepository = imageRepository;
    this.categoryRepository = categoryRepository;
    this.likeRepository = likeRepository;
    this.accessService = accessService;
    this.blogAuthz = blogAuthz;
    this.renderer = renderer;
  }

  /** 블로그 글 목록 (T064). 첫 페이지에만 공지를 같이 준다. size는 10·20·30, 그 밖이면 10. */
  @Transactional(readOnly = true)
  public PostList list(
      Long blogId, Long viewerId, String shareKey, Long categoryId, int size, int page) {
    accessService.checkById(blogId, viewerId, shareKey);
    int pageSize = PAGE_SIZES.contains(size) ? size : 10;
    int index = Math.max(page, 1) - 1;
    Page<Post> posts =
        postRepository.findPublished(blogId, categoryId, PageRequest.of(index, pageSize));
    List<Post> notices =
        index == 0 && categoryId == null
            ? postRepository.findNotices(blogId, PageRequest.of(0, NOTICE_LIMIT))
            : List.of();
    return new PostList(summaries(notices), PageResponse.of(posts, this::summaries));
  }

  /**
   * 글 상세 (T065). 게시 중인 글만. 볼 수 없으면 BlogAccessService가 막는다. 조회수는 컨트롤러가 {@link ViewCountService}로 따로
   * 올린다.
   */
  @Transactional(readOnly = true)
  public PostDetail detail(Long postId, Long viewerId, UserRole viewerRole, String shareKey) {
    Post post =
        postRepository
            .findDetail(postId)
            .filter(Post::isPublished)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    Blog blog = post.getBlog();
    accessService.check(blog, viewerId, shareKey);

    boolean author = viewerId != null && post.isWrittenBy(viewerId);
    boolean canEdit = author && blogAuthz.isMember(blog.getId());
    boolean canDelete =
        author
            || viewerRole == UserRole.ADMIN
            || (viewerId != null
                && blogAuthz.hasPermission(blog.getId(), ManagerPermission.MANAGE_POSTS));
    List<String> tags =
        postTagRepository.findWithTagByPostIdIn(List.of(post.getId())).stream()
            .map(pt -> pt.getTag().getName())
            .toList();
    Category category = post.getCategory();
    return new PostDetail(
        post.getId(),
        post.getTitle(),
        renderer.render(post.getContent()),
        canEdit ? post.getContent() : null,
        authorName(post),
        category == null ? null : category.getId(),
        category == null ? null : category.getName(),
        post.isNotice(),
        tags,
        post.getViewCount(),
        post.getLikeCount(),
        post.getCommentCount(),
        post.getCreatedAt(),
        post.getUpdatedAt(),
        new BlogRef(
            blog.getId(), blog.getSlug(), blog.getName(), ImageService.url(blog.getCoverImage())),
        canEdit,
        canDelete,
        viewerId != null && likeRepository.existsByPostIdAndUserId(post.getId(), viewerId));
  }

  /** 공유 미리보기(og 태그)용 값. 비회원이 볼 수 없는 글이면 null. */
  @Transactional(readOnly = true)
  public OgInfo ogInfo(Long postId, String shareKey) {
    Post post = postRepository.findDetail(postId).filter(Post::isPublished).orElse(null);
    if (post == null) {
      return null;
    }
    try {
      accessService.check(post.getBlog(), null, shareKey);
    } catch (BusinessException e) {
      return null;
    }
    String image =
        imageRepository.findFirstImages(List.of(post.getId())).stream()
            .findFirst()
            .map(i -> ImageService.url(i.getStoredName()))
            .orElse(ImageService.url(post.getBlog().getCoverImage()));
    return new OgInfo(
        post.getTitle(), renderer.excerpt(post.getContent(), 100), image, post.getBlog().getName());
  }

  public record OgInfo(String title, String description, String image, String blogName) {}

  /** 블로그의 카테고리 목록. 블로그를 볼 수 있는 사람만. */
  @Transactional(readOnly = true)
  public List<CategoryRow> categories(Long blogId, Long viewerId, String shareKey) {
    accessService.checkById(blogId, viewerId, shareKey);
    return categoryRepository.findByBlogIdOrderBySortOrderAscIdAsc(blogId).stream()
        .map(c -> new CategoryRow(c.getId(), c.getName(), c.getSortOrder()))
        .toList();
  }

  private List<PostSummary> summaries(List<Post> posts) {
    if (posts.isEmpty()) {
      return List.of();
    }
    List<Long> ids = posts.stream().map(Post::getId).toList();
    Map<Long, String> thumbs =
        imageRepository.findFirstImages(ids).stream()
            .collect(
                Collectors.toMap(
                    i -> i.getPost().getId(),
                    i -> ImageService.url(i.getStoredName()),
                    (a, b) -> a));
    List<PostSummary> rows = new ArrayList<>(posts.size());
    for (Post p : posts) {
      rows.add(
          new PostSummary(
              p.getId(),
              p.getTitle(),
              authorName(p),
              p.getCategory() == null ? null : p.getCategory().getName(),
              p.isNotice(),
              p.getViewCount(),
              p.getLikeCount(),
              p.getCommentCount(),
              thumbs.get(p.getId()),
              p.getCreatedAt()));
    }
    return rows;
  }

  /** 블로그를 떠났거나 탈퇴한 작성자는 "탈퇴한 계정"으로 보인다 (BLG-11). */
  static String authorName(Post post) {
    User author = post.getAuthor();
    if (post.isAuthorHidden() || !author.isActive() || author.getNickname() == null) {
      return HIDDEN_AUTHOR;
    }
    return author.getNickname();
  }
}
