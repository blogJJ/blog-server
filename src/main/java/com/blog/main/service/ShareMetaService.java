package com.blog.main.service;

import com.blog.blog.domain.Blog;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.service.BlogAccessService;
import com.blog.blog.service.ShareLinkService;
import com.blog.board.domain.Post;
import com.blog.board.domain.PostImage;
import com.blog.board.repository.PostImageRepository;
import com.blog.board.repository.PostRepository;
import com.blog.board.service.ContentRenderer;
import com.blog.board.service.ImageService;
import com.blog.common.error.BusinessException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

/**
 * 공유 미리보기(Open Graph·트위터 카드)에 넣을 값 (T161, BRD-07, D-66). 링크를 카카오톡·슬랙·디스코드에 붙이면 미리보기 서버가 HTML만 읽고
 * JS는 돌리지 않아서, 서버가 처음 주는 HTML에 제목·설명·그림을 넣는다.
 *
 * <ul>
 *   <li>비회원이 볼 수 있는 블로그·글만 제목·설명·그림을 채운다. 비공개, 링크 없이 연 일부 공개, 숨김·폐쇄된 블로그, 삭제·숨김 글, 없는 주소는 사이트 기본값만
 *       준다. 일부 공개 블로그를 공유 링크(?share=)로 열면 그 링크를 받은 사람은 볼 수 있으므로 채운다.
 *   <li>설명: 글은 본문에서 마크다운·HTML을 걷어낸 앞 100자, 비면 블로그 소개, 그것도 비면 사이트 소개.
 *   <li>그림(미리보기는 PNG·JPEG 같은 비트맵만 보여 준다): 글의 첫 비트맵 이미지 → 블로그 대표 이미지(비트맵) → Claude가 그린 SVG 대표 그림을
 *       PNG로 바꾼 것({@code /api/og/covers/*.png}) → 사이트 기본 그림({@link #DEFAULT_IMAGE}, 1200x630).
 *   <li>픽셀 장면(cover_scene)은 브라우저 JS로만 그려져 서버에 그림 파일이 없다. 그 블로그는 사이트 기본 그림을 쓴다.
 * </ul>
 */
@Service
public class ShareMetaService {

  public static final String DEFAULT_IMAGE = "/img/og-default.png";
  public static final String COVER_PNG_PREFIX = "/api/og/covers/";

  /** 미리보기에 그대로 쓸 수 있는 비트맵. webp는 보여 주지 않는 곳이 있어 뺀다. */
  private static final Set<String> RASTER = Set.of("jpg", "jpeg", "png", "gif");

  private static final Pattern SVG_NAME =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.svg$");

  private static final int DESCRIPTION_CHARS = 100;

  /**
   * 미리보기 값. 주소는 모두 사이트 안 경로({@code /...})이고 절대 주소는 화면 쪽이 붙인다.
   *
   * @param title og:title
   * @param pageTitle 문서 {@code <title>}. 화면 JS가 나중에 넣는 제목과 같게 둔다
   * @param image 그림 경로
   * @param imageSized 그림이 1200x630으로 정해져 있는지(크기 태그를 붙인다)
   * @param type og:type (website, article)
   * @param url og:url 경로(필요하면 ?share= 포함)
   */
  public record ShareMeta(
      String title,
      String pageTitle,
      String description,
      String image,
      boolean imageSized,
      String type,
      String url) {}

  private record Image(String path, boolean sized) {}

  private static final Image DEFAULT = new Image(DEFAULT_IMAGE, true);

  private final BlogRepository blogRepository;
  private final PostRepository postRepository;
  private final PostImageRepository imageRepository;
  private final BlogAccessService accessService;
  private final ContentRenderer renderer;
  private final String siteName;
  private final String siteDescription;

  public ShareMetaService(
      BlogRepository blogRepository,
      PostRepository postRepository,
      PostImageRepository imageRepository,
      BlogAccessService accessService,
      ContentRenderer renderer,
      @Value("${app.site.name:블로그}") String siteName,
      @Value("${app.site.description:누구나 블로그를 만들고 글을 나누는 곳이에요.}") String siteDescription) {
    this.blogRepository = blogRepository;
    this.postRepository = postRepository;
    this.imageRepository = imageRepository;
    this.accessService = accessService;
    this.renderer = renderer;
    this.siteName = siteName;
    this.siteDescription = siteDescription;
  }

  public String siteName() {
    return siteName;
  }

  /** 메인 화면. */
  public ShareMeta home() {
    return site("/");
  }

  /** 블로그 첫 화면 {@code /blog/{주소}}. */
  @Transactional(readOnly = true)
  public ShareMeta blog(String slug, String shareKey) {
    String path = "/blog/" + slug;
    Blog blog = blogRepository.findBySlug(slug).filter(b -> canSee(b, shareKey)).orElse(null);
    if (blog == null) {
      return site(path);
    }
    Image image = blogImage(blog);
    return new ShareMeta(
        blog.getName(),
        blog.getName() + " - " + siteName,
        firstText(blog.getDescription(), siteDescription),
        image.path(),
        image.sized(),
        "website",
        path + shareQuery(blog, shareKey));
  }

  /** 글 화면 {@code /blog/{주소}/posts/{번호}}. 주소와 글의 블로그가 다르면 기본값. */
  @Transactional(readOnly = true)
  public ShareMeta post(String slug, Long postId, String shareKey) {
    String path = "/blog/" + slug + "/posts/" + postId;
    Post post =
        postRepository
            .findDetail(postId)
            .filter(Post::isPublished)
            .filter(p -> p.getBlog().getSlug().equals(slug))
            .filter(p -> canSee(p.getBlog(), shareKey))
            .orElse(null);
    if (post == null) {
      return site(path);
    }
    Blog blog = post.getBlog();
    Image image =
        imageRepository.findAttached(post.getId()).stream()
            .map(PostImage::getStoredName)
            .filter(ShareMetaService::isRaster)
            .findFirst()
            .map(name -> new Image(ImageService.url(name), false))
            .orElseGet(() -> blogImage(blog));
    return new ShareMeta(
        post.getTitle(),
        post.getTitle() + " - " + blog.getName(),
        firstText(
            renderer.excerpt(post.getContent(), DESCRIPTION_CHARS),
            blog.getDescription(),
            siteDescription),
        image.path(),
        image.sized(),
        "article",
        path + shareQuery(blog, shareKey));
  }

  /** 대표 그림 PNG로 바꿀 SVG 저장 이름. 주소가 맞지 않으면 빈 값. */
  public static Optional<String> coverSvgName(String pngName) {
    if (pngName == null || !pngName.endsWith(".png")) {
      return Optional.empty();
    }
    String svg = pngName.substring(0, pngName.length() - 4) + ".svg";
    return SVG_NAME.matcher(svg).matches() ? Optional.of(svg) : Optional.empty();
  }

  private ShareMeta site(String path) {
    return new ShareMeta(
        siteName, siteName, siteDescription, DEFAULT.path(), DEFAULT.sized(), "website", path);
  }

  /** 비회원 기준으로 볼 수 있는지. 막히는 이유는 묻지 않는다. */
  private boolean canSee(Blog blog, String shareKey) {
    try {
      accessService.check(blog, null, shareKey);
      return true;
    } catch (BusinessException e) {
      return false;
    }
  }

  private static Image blogImage(Blog blog) {
    String cover = blog.getCoverImage();
    if (cover == null) {
      return DEFAULT;
    }
    if (isRaster(cover)) {
      return new Image(ImageService.url(cover), false);
    }
    if (SVG_NAME.matcher(cover).matches()) {
      return new Image(COVER_PNG_PREFIX + cover.substring(0, cover.length() - 4) + ".png", true);
    }
    return DEFAULT;
  }

  private static boolean isRaster(String storedName) {
    int dot = storedName.lastIndexOf('.');
    return dot > 0 && RASTER.contains(storedName.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /** 일부 공개 블로그를 공유 링크로 열었을 때만 링크 값을 붙인다. 미리보기를 누른 사람도 같은 링크로 들어가야 보인다. */
  private static String shareQuery(Blog blog, String shareKey) {
    return ShareLinkService.matches(blog, shareKey)
        ? "?share=" + UriUtils.encodeQueryParam(shareKey, "UTF-8")
        : "";
  }

  /** 비지 않은 첫 값. 블로그 소개(500자까지)도 미리보기에 맞게 앞 100자로 자른다. */
  private static String firstText(String... candidates) {
    for (String c : candidates) {
      if (c != null && !c.isBlank()) {
        String text = c.strip().replaceAll("\\s+", " ");
        if (text.codePointCount(0, text.length()) <= DESCRIPTION_CHARS) {
          return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, DESCRIPTION_CHARS)) + "…";
      }
    }
    return "";
  }
}
