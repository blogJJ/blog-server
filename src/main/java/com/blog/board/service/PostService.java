package com.blog.board.service;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.ManagerPermission;
import com.blog.blog.repository.BlogRepository;
import com.blog.board.domain.Category;
import com.blog.board.domain.Post;
import com.blog.board.domain.PostImage;
import com.blog.board.domain.PostStatus;
import com.blog.board.domain.PostTag;
import com.blog.board.domain.Tag;
import com.blog.board.repository.CategoryRepository;
import com.blog.board.repository.PostImageRepository;
import com.blog.board.repository.PostRepository;
import com.blog.board.repository.PostTagRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.AccountGuard;
import com.blog.common.security.AccountGuard.Activity;
import com.blog.common.security.BlogAuthz;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 글 쓰기·수정·삭제 (T063, BRD-01, BRD-04, BRD-05, SEC-07, D-07, D-58).
 *
 * <ul>
 *   <li>쓰기: 그 블로그 멤버만(블로그 안에서 정지된 멤버 제외). 계정 정지·관리자 계정은 못 쓴다 (AccountGuard).
 *   <li>공지로 올리기: 블로그장 또는 글 관리 권한 부블로그장.
 *   <li>수정: 작성자만. 블로그장·관리자도 남의 글은 고치지 않는다 (D-07, D-58).
 *   <li>삭제: 작성자, 블로그장·글 관리 권한 부블로그장(그 블로그 글), 메인 관리자. 남이 지우면 작성자에게 "내 글 삭제됨" 알림.
 *   <li>이미지: 본문에 들어 있는 {@code /api/images/...} 가운데 작성자가 올린 것만 이 글에 연결하고, 본문에서 빠진 것은 연결을 끊는다. 10장까지.
 * </ul>
 */
@Service
public class PostService {

  /** 화면에서 받은 값 */
  public record PostForm(
      String title, String content, Long categoryId, List<String> tags, Boolean notice) {}

  private final PostRepository postRepository;
  private final PostTagRepository postTagRepository;
  private final PostImageRepository imageRepository;
  private final CategoryRepository categoryRepository;
  private final BlogRepository blogRepository;
  private final UserRepository userRepository;
  private final TagService tagService;
  private final BlogAuthz blogAuthz;
  private final AccountGuard accountGuard;
  private final NotificationService notificationService;
  private final Clock clock;

  public PostService(
      PostRepository postRepository,
      PostTagRepository postTagRepository,
      PostImageRepository imageRepository,
      CategoryRepository categoryRepository,
      BlogRepository blogRepository,
      UserRepository userRepository,
      TagService tagService,
      BlogAuthz blogAuthz,
      AccountGuard accountGuard,
      NotificationService notificationService,
      Clock clock) {
    this.postRepository = postRepository;
    this.postTagRepository = postTagRepository;
    this.imageRepository = imageRepository;
    this.categoryRepository = categoryRepository;
    this.blogRepository = blogRepository;
    this.userRepository = userRepository;
    this.tagService = tagService;
    this.blogAuthz = blogAuthz;
    this.accountGuard = accountGuard;
    this.notificationService = notificationService;
    this.clock = clock;
  }

  /** 글을 쓴다. */
  @Transactional
  public Post create(Long blogId, Long userId, PostForm form) {
    accountGuard.check(userId, Activity.WRITE_POST);
    Blog blog =
        blogRepository
            .findById(blogId)
            .filter(Blog::isOpen)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (!blogAuthz.isMember(blogId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "이 블로그 멤버만 글을 쓸 수 있어요.");
    }
    Valid v = validate(blogId, form);
    Post post =
        postRepository.save(
            new Post(
                blog,
                userRepository.getReferenceById(userId),
                v.category(),
                v.title(),
                v.content(),
                v.notice()));
    saveTags(post, v.tags());
    attachImages(post, userId);
    blogRepository.addPostCount(blogId, 1);
    return post;
  }

  /** 작성자만 고친다. */
  @Transactional
  public Post update(Long postId, Long userId, PostForm form) {
    accountGuard.check(userId, Activity.WRITE_POST);
    Post post = livePost(postId);
    if (!post.isWrittenBy(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "글은 쓴 사람만 고칠 수 있어요.");
    }
    Long blogId = post.getBlog().getId();
    if (!blogAuthz.isMember(blogId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "이 블로그 멤버만 글을 고칠 수 있어요.");
    }
    Valid v = validate(blogId, form);
    if (post.isNotice() && !v.notice() && !canManagePosts(blogId)) {
      // 블로그장이 공지로 올린 글을 작성자가 내리지 못하게 그대로 둔다
      v = v.withNotice(true);
    }
    post.edit(v.category(), v.title(), v.content(), v.notice());
    postTagRepository.deleteByPostId(post.getId());
    saveTags(post, v.tags());
    attachImages(post, userId);
    return post;
  }

  /**
   * 지운다. 행은 남기고 DELETED로 바꾼다 (30일 보관).
   *
   * @param role 지금 요청한 회원의 역할 (메인 관리자는 모든 글을 지울 수 있다)
   */
  @Transactional
  public void delete(Long postId, Long userId, UserRole role) {
    Post post = livePost(postId);
    Long blogId = post.getBlog().getId();
    boolean author = post.isWrittenBy(userId);
    boolean admin = role == UserRole.ADMIN;
    if (!author && !admin && !canManagePosts(blogId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "이 글을 지울 권한이 없어요.");
    }
    User by = userRepository.getReferenceById(userId);
    post.delete(by, LocalDateTime.now(clock));
    blogRepository.addPostCount(blogId, -1);
    if (!author) {
      notificationService.notify(
          post.getAuthor().getId(),
          userId,
          NotificationType.POST_DELETED,
          NotificationTargetType.BLOG,
          blogId,
          (admin ? "관리자" : "블로그 관리자") + "가 내 글 '" + post.getTitle() + "'을(를) 삭제했어요.");
    }
  }

  /** 블로그장 또는 글 관리 권한 부블로그장인지 (지금 로그인한 회원 기준). */
  public boolean canManagePosts(Long blogId) {
    return blogAuthz.hasPermission(blogId, ManagerPermission.MANAGE_POSTS);
  }

  private Post livePost(Long postId) {
    Post post =
        postRepository
            .findById(postId)
            .filter(p -> p.getStatus() != PostStatus.DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (!post.getBlog().isOpen()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    return post;
  }

  private record Valid(
      String title, String content, Category category, List<String> tags, boolean notice) {
    Valid withNotice(boolean n) {
      return new Valid(title, content, category, tags, n);
    }
  }

  private Valid validate(Long blogId, PostForm form) {
    String title = form.title() == null ? "" : form.title().strip();
    int titleLength = title.codePointCount(0, title.length());
    if (titleLength < 1 || titleLength > Post.TITLE_MAX) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "제목은 1~30자로 입력해 주세요.");
    }
    String content = ContentRenderer.validate(form.content());
    Category category = null;
    if (form.categoryId() != null) {
      category =
          categoryRepository
              .findById(form.categoryId())
              .filter(c -> c.getBlog().getId().equals(blogId))
              .orElseThrow(
                  () -> new BusinessException(ErrorCode.INVALID_INPUT, "카테고리를 다시 골라 주세요."));
    }
    boolean notice = Boolean.TRUE.equals(form.notice());
    if (notice && !canManagePosts(blogId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "공지는 블로그장과 글 관리 권한이 있는 부블로그장만 올릴 수 있어요.");
    }
    return new Valid(title, content, category, TagNormalizer.normalizeAll(form.tags()), notice);
  }

  private void saveTags(Post post, List<String> names) {
    for (Tag tag : tagService.findOrCreate(names)) {
      postTagRepository.save(new PostTag(post, tag));
    }
  }

  /** 본문의 이미지를 이 글에 연결한다. 작성자가 올린 것만, 10장까지. */
  private void attachImages(Post post, Long userId) {
    List<String> names = ContentRenderer.imageNames(post.getContent());
    if (names.size() > PostImage.MAX_PER_POST) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "이미지는 글 하나에 10장까지 넣을 수 있어요.");
    }
    Map<String, PostImage> found =
        imageRepository.findByStoredNameIn(names).stream()
            .filter(i -> i.getUploader().getId().equals(userId))
            .filter(i -> i.getPost() == null || i.getPost().getId().equals(post.getId()))
            .collect(Collectors.toMap(PostImage::getStoredName, Function.identity()));
    Set<Long> keep = new HashSet<>();
    int order = 0;
    for (String name : names) {
      PostImage image = found.get(name);
      if (image != null) {
        image.attach(post, order++);
        keep.add(image.getId());
      }
    }
    if (post.getId() != null) {
      for (PostImage old : imageRepository.findByPostIdOrderBySortOrderAsc(post.getId())) {
        if (!keep.contains(old.getId())) {
          old.detach();
        }
      }
    }
  }
}
