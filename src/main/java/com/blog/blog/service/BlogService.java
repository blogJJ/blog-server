package com.blog.blog.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.BlogMemberRole;
import com.blog.blog.domain.BlogTag;
import com.blog.blog.domain.BlogVisibility;
import com.blog.blog.domain.JoinPolicy;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.repository.BlogSubscriptionRepository;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import com.blog.blog.repository.BlogTagRepository;
import com.blog.board.domain.Tag;
import com.blog.board.service.TagNormalizer;
import com.blog.board.service.TagService;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.AccountGuard;
import com.blog.common.security.AccountGuard.Activity;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 만들기와 정보 수정 (T044, T045, T053, BLG-01, BLG-10).
 *
 * <ul>
 *   <li>주소: 영문 소문자·숫자·{@code -} 3~30자, 중복 불가, 예약어 불가. 만든 뒤에는 바꾸지 않는다 (D-70).
 *   <li>이름은 중복을 허용한다. 1~50자, 소개는 500자까지.
 *   <li>생성 개수: 공개(일부 공개 포함) {@code blog.limit.public}개(기본 3, 최대 5), 비공개 5개. 회원 행을 FOR UPDATE로 잠근 뒤
 *       세므로 동시에 눌러도 넘지 않는다 (D-68, SC-006). 공개 범위를 바꿀 때도 같은 제한을 본다.
 *   <li>만든 회원은 blog_members에 OWNER로 들어간다.
 * </ul>
 */
@Service
public class BlogService {

  public static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{3,30}$");

  /** 화면 주소와 겹치거나 운영 주체로 오해할 수 있는 주소 */
  public static final Set<String> RESERVED_SLUGS =
      Set.of(
          "main",
          "admin",
          "api",
          "login",
          "logout",
          "signup",
          "search",
          "blog",
          "blogs",
          "new",
          "me",
          "my",
          "mypage",
          "settings",
          "help",
          "notice",
          "notices",
          "privacy",
          "terms",
          "static",
          "css",
          "js",
          "images",
          "error",
          "actuator",
          "root",
          "system",
          "manager",
          "official",
          "support");

  public static final int NAME_MAX = 50;
  public static final int DESCRIPTION_MAX = 500;

  /** 화면에서 받은 값 */
  public record BlogForm(
      String name,
      String description,
      BlogVisibility visibility,
      JoinPolicy joinPolicy,
      List<String> tags) {}

  private final BlogRepository blogRepository;
  private final BlogMemberRepository memberRepository;
  private final BlogTagRepository blogTagRepository;
  private final UserRepository userRepository;
  private final TagService tagService;
  private final AccountGuard accountGuard;
  private final BlogSubscriptionRepository subscriptionRepository;
  private final NotificationService notificationService;
  private final int publicLimit;
  private final int privateLimit;

  public BlogService(
      BlogRepository blogRepository,
      BlogMemberRepository memberRepository,
      BlogTagRepository blogTagRepository,
      UserRepository userRepository,
      TagService tagService,
      AccountGuard accountGuard,
      BlogSubscriptionRepository subscriptionRepository,
      NotificationService notificationService,
      @Value("${blog.limit.public:3}") int publicLimit,
      @Value("${blog.limit.private:5}") int privateLimit) {
    if (publicLimit < 1 || publicLimit > 5) {
      throw new IllegalArgumentException("blog.limit.public must be 1..5: " + publicLimit);
    }
    this.blogRepository = blogRepository;
    this.memberRepository = memberRepository;
    this.blogTagRepository = blogTagRepository;
    this.userRepository = userRepository;
    this.tagService = tagService;
    this.accountGuard = accountGuard;
    this.subscriptionRepository = subscriptionRepository;
    this.notificationService = notificationService;
    this.publicLimit = publicLimit;
    this.privateLimit = privateLimit;
  }

  /** 블로그를 만든다 (T044, T045). */
  @Transactional
  public Blog create(Long userId, String rawSlug, BlogForm form) {
    String slug = validateSlug(rawSlug);
    Form f = validate(form);
    User owner =
        userRepository
            .findByIdForUpdate(userId)
            .filter(User::isActive)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    accountGuard.check(owner, Activity.CREATE_BLOG);
    checkLimit(owner.getId(), f.visibility(), null);
    if (blogRepository.existsBySlug(slug)) {
      throw duplicateSlug();
    }

    Blog blog = new Blog(owner, slug, f.name(), f.description(), f.visibility(), f.joinPolicy());
    ShareLinkService.syncWithVisibility(blog);
    try {
      blogRepository.saveAndFlush(blog);
    } catch (DataIntegrityViolationException e) {
      throw duplicateSlug(); // 같은 주소를 동시에 만든 경우
    }
    memberRepository.save(new BlogMember(blog, owner, BlogMemberRole.OWNER));
    saveTags(blog, f.tags());
    return blog;
  }

  /**
   * 정보 수정 (T053, T109). 권한(블로그장 또는 EDIT_INFO)은 컨트롤러가 확인한다. 비공개로 바꾸면 구독은 그대로 두고, 멤버가 아닌 구독자에게
   * 알린다. 그 구독자에게는 피드의 구독 탭에서도 글이 안 보인다 (BLG-01, D-50).
   */
  @Transactional
  public Blog update(Long blogId, BlogForm form) {
    Form f = validate(form);
    Blog blog =
        blogRepository
            .findById(blogId)
            .filter(Blog::isOpen)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (isPrivate(blog.getVisibility()) != isPrivate(f.visibility())) {
      // 개수는 블로그장 기준. 블로그장 행을 잠가 생성과 같은 순서로 센다
      userRepository.findByIdForUpdate(blog.getOwner().getId());
      checkLimit(blog.getOwner().getId(), f.visibility(), blog.getId());
    }
    boolean becomesPrivate =
        !isPrivate(blog.getVisibility()) && isPrivate(f.visibility());
    blog.updateInfo(f.name(), f.description(), f.visibility(), f.joinPolicy());
    ShareLinkService.syncWithVisibility(blog);
    blogTagRepository.deleteByBlogId(blog.getId());
    saveTags(blog, f.tags());
    if (becomesPrivate) {
      notificationService.notifyAll(
          subscriptionRepository.findNonMemberSubscriberIds(blog.getId()),
          null,
          NotificationType.BLOG_PRIVATE,
          NotificationTargetType.BLOG,
          blog.getId(),
          "구독한 블로그 '" + blog.getName() + "'이(가) 비공개로 바뀌었어요. 이제 멤버만 글을 볼 수 있어요.");
    }
    return blog;
  }

  /**
   * 대표 이미지를 바꾼다. 권한(블로그장 또는 EDIT_INFO)은 컨트롤러가 확인한다.
   *
   * @param storedName 새 이미지 저장 이름. null이면 지운다
   * @return 이전 이미지 저장 이름 (파일을 지우는 데 쓴다). 없으면 null
   */
  @Transactional
  public String changeCover(Long blogId, String storedName) {
    Blog blog =
        blogRepository
            .findById(blogId)
            .filter(Blog::isOpen)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    String old = blog.getCoverImage();
    blog.changeCoverImage(storedName);
    return old;
  }

  /** 주소 규칙 확인. 앞뒤 공백은 지우고 대문자는 소문자로 바꾼다. */
  public static String validateSlug(String raw) {
    String slug = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    if (!SLUG.matcher(slug).matches() || slug.startsWith("-") || slug.endsWith("-")) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "주소는 영문 소문자, 숫자, -로 3~30자 입력해 주세요. -로 시작하거나 끝날 수 없어요.");
    }
    if (RESERVED_SLUGS.contains(slug)) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "쓸 수 없는 주소예요. 다른 주소를 입력해 주세요.");
    }
    return slug;
  }

  /** 주소를 쓸 수 있는지 (만들기 화면의 중복 확인). */
  @Transactional(readOnly = true)
  public boolean isSlugAvailable(String raw) {
    return !blogRepository.existsBySlug(validateSlug(raw));
  }

  private record Form(
      String name,
      String description,
      BlogVisibility visibility,
      JoinPolicy joinPolicy,
      List<String> tags) {}

  private static Form validate(BlogForm form) {
    String name = form.name() == null ? "" : form.name().strip();
    if (name.isEmpty() || name.length() > NAME_MAX) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "블로그 이름은 1~50자로 입력해 주세요.");
    }
    String description = form.description() == null ? null : form.description().strip();
    if (description != null && description.isEmpty()) {
      description = null;
    }
    if (description != null && description.length() > DESCRIPTION_MAX) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "소개는 500자까지 쓸 수 있어요.");
    }
    if (form.visibility() == null || form.joinPolicy() == null) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "공개 범위와 참여 방식을 골라 주세요.");
    }
    return new Form(
        name,
        description,
        form.visibility(),
        form.joinPolicy(),
        TagNormalizer.normalizeAll(form.tags()));
  }

  private void checkLimit(Long ownerId, BlogVisibility visibility, Long excludeBlogId) {
    boolean priv = isPrivate(visibility);
    List<BlogVisibility> group =
        priv
            ? List.of(BlogVisibility.PRIVATE)
            : List.of(BlogVisibility.PUBLIC, BlogVisibility.LINK_ONLY);
    long count = blogRepository.countOwned(ownerId, group, excludeBlogId);
    int limit = priv ? privateLimit : publicLimit;
    if (count >= limit) {
      String kind = priv ? "비공개" : "공개(일부 공개 포함)";
      throw new BusinessException(ErrorCode.CONFLICT, kind + " 블로그는 " + limit + "개까지 만들 수 있어요.");
    }
  }

  private static boolean isPrivate(BlogVisibility visibility) {
    return visibility == BlogVisibility.PRIVATE;
  }

  private void saveTags(Blog blog, List<String> names) {
    for (Tag tag : tagService.findOrCreate(names)) {
      blogTagRepository.save(new BlogTag(blog, tag));
    }
  }

  private static BusinessException duplicateSlug() {
    return new BusinessException(ErrorCode.CONFLICT, "이미 쓰고 있는 주소예요. 다른 주소를 입력해 주세요.");
  }
}
