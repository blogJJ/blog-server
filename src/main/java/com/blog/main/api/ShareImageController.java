package com.blog.main.api;

import com.blog.board.service.ImageService;
import com.blog.board.service.SvgRasterizer;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.main.service.ShareMetaService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Claude가 그린 SVG 대표 그림의 공유 미리보기용 PNG (T161). 미리보기 서버(카카오톡·슬랙·디스코드 등)는 SVG를 보여 주지 않는다.
 *
 * <ul>
 *   <li>주소 {@code /api/og/covers/{UUID}.png}는 저장된 {@code {UUID}.svg}와 짝이다. SVG 원본도 {@code
 *       /api/images/}로 누구나 받을 수 있으니 PNG로 바꿔 줘도 더 드러나는 것은 없다.
 *   <li>저장 이름이 UUID라 내용이 바뀌지 않는다. 브라우저·미리보기 서버가 30일 캐시하고, 서버도 최근 것 몇 장을 메모리에 둔다.
 *   <li>그리기는 CPU를 쓰므로 한 번에 두 장까지만 그린다. 3초 넘게 기다리면 503.
 * </ul>
 */
@RestController
public class ShareImageController {

  private static final Logger log = LoggerFactory.getLogger(ShareImageController.class);
  private static final int CACHE_ENTRIES = 64;

  private final ImageService imageService;
  private final Semaphore rendering = new Semaphore(2);
  private final Map<String, byte[]> cache =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
          return size() > CACHE_ENTRIES;
        }
      };

  public ShareImageController(ImageService imageService) {
    this.imageService = imageService;
  }

  @GetMapping(ShareMetaService.COVER_PNG_PREFIX + "{name:.+}")
  public ResponseEntity<byte[]> cover(@PathVariable String name) {
    String svgName =
        ShareMetaService.coverSvgName(name)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    byte[] png = cached(svgName);
    if (png == null) {
      png = render(svgName);
    }
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
        .body(png);
  }

  private byte[] render(String svgName) {
    byte[] svg;
    try (InputStream in = imageService.open(svgName)) {
      svg = in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    try {
      if (!rendering.tryAcquire(3, TimeUnit.SECONDS)) {
        throw new BusinessException(ErrorCode.REQUEST_TIMEOUT);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.REQUEST_TIMEOUT);
    }
    try {
      byte[] png = cached(svgName);
      if (png == null) {
        png = SvgRasterizer.toPng(svg);
        synchronized (cache) {
          cache.put(svgName, png);
        }
      }
      return png;
    } catch (RuntimeException e) {
      log.warn("대표 그림 {}을 PNG로 바꾸지 못함: {}", svgName, e.toString());
      throw new BusinessException(ErrorCode.NOT_FOUND);
    } finally {
      rendering.release();
    }
  }

  private byte[] cached(String svgName) {
    synchronized (cache) {
      return cache.get(svgName);
    }
  }
}
