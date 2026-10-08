package com.blog.blog.service;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogVisibility;
import com.blog.blog.repository.BlogRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일부 공개 블로그의 공유 링크 (T046, BLG-01, D-37, D-49, D-50).
 *
 * <ul>
 *   <li>링크는 {@code /blog/{주소}?share={값}}. 값은 주소와 별개인 무작위 32바이트라 주소를 알아도 추측할 수 없다.
 *   <li>새로 만들면 이전 값은 바로 무효가 된다.
 *   <li>일부 공개가 아닌 블로그에는 링크가 없다. 일부 공개에서 다른 범위로 바꾸면 값을 지운다.
 * </ul>
 */
@Service
public class ShareLinkService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final BlogRepository blogRepository;

  public ShareLinkService(BlogRepository blogRepository) {
    this.blogRepository = blogRepository;
  }

  /** 새 무작위 값. URL에 그대로 쓸 수 있는 43자. */
  public static String newKey() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** 공개 범위에 맞게 값을 둔다. 일부 공개면 없을 때 만들고, 아니면 지운다. */
  public static void syncWithVisibility(Blog blog) {
    if (blog.getVisibility() == BlogVisibility.LINK_ONLY) {
      if (blog.getShareKey() == null) {
        blog.changeShareKey(newKey());
      }
    } else {
      blog.changeShareKey(null);
    }
  }

  /** 링크 값이 맞는지. 시간 차로 값을 알아내지 못하게 비교한다. */
  public static boolean matches(Blog blog, String key) {
    if (blog.getShareKey() == null || key == null || key.isEmpty()) {
      return false;
    }
    return MessageDigest.isEqual(
        blog.getShareKey().getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
  }

  /** 링크를 새로 만든다. 권한 확인은 컨트롤러(@PreAuthorize)가 한다. */
  @Transactional
  public String regenerate(Long blogId) {
    Blog blog =
        blogRepository
            .findById(blogId)
            .filter(Blog::isOpen)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (blog.getVisibility() != BlogVisibility.LINK_ONLY) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "일부 공개 블로그만 공유 링크가 있어요.");
    }
    blog.changeShareKey(newKey());
    return blog.getShareKey();
  }
}
