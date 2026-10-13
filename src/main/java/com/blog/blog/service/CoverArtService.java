package com.blog.blog.service;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogTag;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.repository.BlogTagRepository;
import com.blog.board.domain.Tag;
import com.blog.board.service.ImageService;
import com.blog.board.service.SvgSanitizer;
import com.blog.common.storage.FileStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 블로그를 만들 때 Claude가 대표 그림을 새로 그려 넣는다 (T155, BLG-15, D-126). 같은 장면이면 비슷해 보이던 픽셀 장면(D-119) 대신 블로그마다
 * 다른 그림을 보여 주려는 것이다.
 *
 * <ul>
 *   <li>블로그를 만든 커밋 뒤 마스코트 일꾼({@link MascotService})이 부른다. 블로그 만들기는 기다리지 않는다.
 *   <li>이름·태그를 고칠 때는 다시 그리지 않는다(비용). 이미 있는 블로그도 채우지 않는다.
 *   <li>그림은 정리한 SVG 파일로 저장하고 대표 이미지(cover_image)가 비어 있을 때만 넣는다. 블로그장이 올린 이미지는 덮어쓰지 않는다. 블로그장은 평소처럼
 *       다른 이미지로 바꾸거나 지울 수 있고, 지우면 픽셀 장면으로 돌아간다.
 *   <li>키가 없거나, 실패하거나, 거절하거나, 정리하다 버리면 아무것도 바꾸지 않는다(픽셀 장면 그대로).
 * </ul>
 */
@Service
public class CoverArtService {

  private static final Logger log = LoggerFactory.getLogger(CoverArtService.class);

  private final BlogRepository blogRepository;
  private final BlogTagRepository blogTagRepository;
  private final ClaudeCoverArtClient claude;
  private final FileStorage storage;
  private final ImageService imageService;
  private final TransactionTemplate tx;
  private final boolean enabled;

  public CoverArtService(
      BlogRepository blogRepository,
      BlogTagRepository blogTagRepository,
      ClaudeCoverArtClient claude,
      FileStorage storage,
      ImageService imageService,
      PlatformTransactionManager transactionManager,
      @Value("${app.cover-art.enabled:true}") boolean enabled) {
    this.blogRepository = blogRepository;
    this.blogTagRepository = blogTagRepository;
    this.claude = claude;
    this.storage = storage;
    this.imageService = imageService;
    this.tx = new TransactionTemplate(transactionManager);
    this.enabled = enabled;
  }

  /**
   * 대표 그림을 그려 저장한다. Claude 호출은 트랜잭션 밖에서 한다.
   *
   * @return 넣은 파일 저장 이름. 그리지 않았거나 넣지 못했으면 빈 값
   */
  public Optional<String> generate(Long blogId) {
    if (!enabled || !claude.enabled()) {
      return Optional.empty();
    }
    Optional<Info> found =
        Objects.requireNonNullElse(
            tx.execute(
                s ->
                    blogRepository
                        .findById(blogId)
                        .filter(Blog::isOpen)
                        .filter(b -> b.getCoverImage() == null)
                        .map(b -> new Info(b.getName(), tagNames(b.getId())))),
            Optional.empty());
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Info info = found.get();
    Optional<String> drawn = claude.draw(info.name(), info.tags());
    if (drawn.isEmpty()) {
      return Optional.empty();
    }
    String svg;
    try {
      svg = SvgSanitizer.sanitize(drawn.get());
    } catch (IllegalArgumentException e) {
      log.warn("블로그 {} 대표 그림을 정리하다 버림: {}", blogId, e.getMessage());
      return Optional.empty();
    }
    String stored;
    try {
      stored = storage.store(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)), "svg");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    int filled;
    try {
      filled =
          Objects.requireNonNullElse(
              tx.execute(s -> blogRepository.fillEmptyCoverImage(blogId, stored)), 0);
    } catch (RuntimeException e) {
      imageService.deleteQuietly(stored);
      throw e;
    }
    if (filled == 0) {
      // 그리는 동안 블로그장이 이미지를 올렸거나 블로그가 닫혔다
      imageService.deleteQuietly(stored);
      return Optional.empty();
    }
    return Optional.of(stored);
  }

  /** 실패해도 블로그에는 영향이 없다. 경고만 남긴다. */
  void generateSafely(Long blogId) {
    try {
      generate(blogId);
    } catch (RuntimeException e) {
      log.warn("블로그 {} 대표 그림을 저장하지 못함: {}", blogId, e.toString());
    }
  }

  private List<String> tagNames(Long blogId) {
    return blogTagRepository.findWithTagByBlogIdIn(List.of(blogId)).stream()
        .map(BlogTag::getTag)
        .map(Tag::getName)
        .toList();
  }

  private record Info(String name, List<String> tags) {}
}
