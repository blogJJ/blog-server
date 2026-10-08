package com.blog.board.api;

import com.blog.board.service.ImageService;
import com.blog.board.service.ImageService.Uploaded;
import com.blog.board.service.ImageType;
import com.blog.common.ratelimit.RateLimitGuard;
import com.blog.common.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.time.Duration;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 이미지 올리기·보기 (T062, BRD-05). */
@RestController
public class ImageController {

  private final ImageService imageService;
  private final RateLimitGuard rateLimitGuard;

  public ImageController(ImageService imageService, RateLimitGuard rateLimitGuard) {
    this.imageService = imageService;
    this.rateLimitGuard = rateLimitGuard;
  }

  /** 글 이미지 한 장. 글쓰기 화면이 받은 url을 본문에 넣는다 */
  @PostMapping("/api/images")
  @ResponseStatus(HttpStatus.CREATED)
  public Uploaded upload(
      @AuthenticationPrincipal AuthUser user,
      @RequestParam("file") MultipartFile file,
      HttpServletRequest request) {
    rateLimitGuard.check("image-upload", request, 100, Duration.ofMinutes(10));
    return imageService.uploadPostImage(user.id(), file);
  }

  /** 이미지 보기. 이름이 UUID라 바뀌지 않으므로 오래 캐시한다 */
  @GetMapping("/api/images/{storedName:.+}")
  public ResponseEntity<InputStreamResource> view(@PathVariable String storedName) {
    InputStream in = imageService.open(storedName);
    MediaType type =
        ImageType.byStoredName(storedName)
            .map(t -> MediaType.parseMediaType(t.mimeType()))
            .orElse(MediaType.APPLICATION_OCTET_STREAM);
    return ResponseEntity.ok()
        .contentType(type)
        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
        .body(new InputStreamResource(in));
  }
}
