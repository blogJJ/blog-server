package com.blog.main.api;

import com.blog.common.security.AuthUser;
import com.blog.main.service.SimilarPostService;
import com.blog.main.service.SimilarPostService.SimilarPost;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 글 아래 "이런 글은 어때요" (T168). 글 화면이 본문을 다 그린 뒤 아래쪽에 가까워지면 부른다. 보는 사람마다 차단 목록이 달라서 브라우저에만 잠깐 캐시한다. */
@RestController
public class SimilarPostController {

  private final SimilarPostService similarPostService;

  public SimilarPostController(SimilarPostService similarPostService) {
    this.similarPostService = similarPostService;
  }

  /**
   * @param limit 1~8, 기본 4
   * @param share 원래 글이 일부 공개 블로그면 공유 링크 값
   */
  @GetMapping("/api/posts/{postId}/similar")
  public ResponseEntity<List<SimilarPost>> similar(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long postId,
      @RequestParam(defaultValue = "" + SimilarPostService.DEFAULT_LIMIT) int limit,
      @RequestParam(required = false) String share) {
    List<SimilarPost> posts =
        similarPostService.similar(postId, user == null ? null : user.id(), share, limit);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate())
        .body(posts);
  }
}
