package com.blog.board.api;

import com.blog.board.service.WritingAssistService;
import com.blog.board.service.WritingAssistService.Features;
import com.blog.board.service.WritingAssistService.SpellingResult;
import com.blog.board.service.WritingAssistService.TagSuggestions;
import com.blog.common.security.AuthUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 글쓰기 화면 도우미 (T167). 태그 후보와 맞춤법 후보만 돌려주고 아무것도 저장하지 않는다. 쓰기(POST)는 로그인이 필요하다(SecurityConfig의 /api/**
 * 규칙).
 */
@RestController
public class WritingAssistController {

  private final WritingAssistService service;

  public WritingAssistController(WritingAssistService service) {
    this.service = service;
  }

  public record TagRequest(Long blogId, String title, String content, List<String> tags) {}

  public record SpellingRequest(Long blogId, String content) {}

  /** 화면이 어떤 버튼을 보일지. 맞춤법 검사는 Claude 키가 있을 때만 */
  @GetMapping("/api/posts/assist/config")
  public Features config() {
    return service.features();
  }

  @PostMapping("/api/posts/assist/tags")
  public TagSuggestions tags(@AuthenticationPrincipal AuthUser user, @RequestBody TagRequest req) {
    return service.suggestTags(user.id(), req.blogId(), req.title(), req.content(), req.tags());
  }

  @PostMapping("/api/posts/assist/spelling")
  public SpellingResult spelling(
      @AuthenticationPrincipal AuthUser user, @RequestBody SpellingRequest req) {
    return service.checkSpelling(user.id(), req.blogId(), req.content());
  }
}
