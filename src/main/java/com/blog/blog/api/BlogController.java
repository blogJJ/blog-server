package com.blog.blog.api;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogVisibility;
import com.blog.blog.domain.JoinPolicy;
import com.blog.blog.service.BlogQueryService;
import com.blog.blog.service.BlogQueryService.BlogDetail;
import com.blog.blog.service.BlogQueryService.BlogSummary;
import com.blog.blog.service.BlogQueryService.MyBlogs;
import com.blog.blog.service.BlogService;
import com.blog.blog.service.BlogService.BlogForm;
import com.blog.blog.service.ShareLinkService;
import com.blog.common.security.AuthUser;
import com.blog.common.web.PageResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 블로그 API (T044~T049, T052, T053, BLG-01~03, BLG-06, BLG-10). */
@RestController
public class BlogController {

  private final BlogService blogService;
  private final BlogQueryService queryService;
  private final ShareLinkService shareLinkService;

  public BlogController(
      BlogService blogService, BlogQueryService queryService, ShareLinkService shareLinkService) {
    this.blogService = blogService;
    this.queryService = queryService;
    this.shareLinkService = shareLinkService;
  }

  /** 만들기 요청. form 값에 주소(slug)를 더한다. */
  public record CreateRequest(
      String slug,
      String name,
      String description,
      BlogVisibility visibility,
      JoinPolicy joinPolicy,
      List<String> tags) {

    BlogForm form() {
      return new BlogForm(name, description, visibility, joinPolicy, tags);
    }
  }

  public record CreatedResponse(Long id, String slug, String shareKey) {}

  /** 블로그 만들기 (T044, T045) */
  @PostMapping("/api/blogs")
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedResponse create(
      @AuthenticationPrincipal AuthUser user, @RequestBody CreateRequest body) {
    Blog blog = blogService.create(user.id(), body.slug(), body.form());
    return new CreatedResponse(blog.getId(), blog.getSlug(), blog.getShareKey());
  }

  /** 만들기 화면의 주소 중복 확인. 규칙에 맞지 않으면 400 */
  @GetMapping("/api/blogs/slug-check")
  public Map<String, Object> checkSlug(@RequestParam String slug) {
    boolean available = blogService.isSlugAvailable(slug);
    return Map.of("available", available, "message", available ? "쓸 수 있는 주소예요." : "이미 쓰고 있는 주소예요.");
  }

  /** 메인 블로그 목록 (T048) */
  @GetMapping("/api/blogs")
  public PageResponse<BlogSummary> list(
      @RequestParam(defaultValue = "latest") String sort,
      @RequestParam(defaultValue = "1") int page) {
    return queryService.list(sort, page);
  }

  /** 블로그 검색 (T049) */
  @GetMapping("/api/blogs/search")
  public PageResponse<BlogSummary> search(
      @RequestParam String q, @RequestParam(defaultValue = "1") int page) {
    return queryService.search(q, page);
  }

  /** 블로그 첫 화면. 일부 공개는 share 값이 있어야 한다 (T047, T055) */
  @GetMapping("/api/blogs/by-slug/{slug}")
  public BlogDetail detail(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable String slug,
      @RequestParam(required = false) String share) {
    return queryService.detail(slug, user == null ? null : user.id(), share);
  }

  /** 정보 수정 (T053). 블로그장 또는 정보 수정 권한 부블로그장 */
  @PutMapping("/api/blogs/{blogId}")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'EDIT_INFO')")
  public CreatedResponse update(@PathVariable Long blogId, @RequestBody BlogForm body) {
    Blog blog = blogService.update(blogId, body);
    return new CreatedResponse(blog.getId(), blog.getSlug(), blog.getShareKey());
  }

  /** 공유 링크 새로 만들기 (T046). 이전 링크는 바로 막힌다 */
  @PostMapping("/api/blogs/{blogId}/share-link")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'EDIT_INFO')")
  public Map<String, String> regenerateShareLink(@PathVariable Long blogId) {
    return Map.of("shareKey", shareLinkService.regenerate(blogId));
  }

  /** 내 블로그 목록 (T052) */
  @GetMapping("/api/me/blogs")
  public MyBlogs myBlogs(@AuthenticationPrincipal AuthUser user) {
    return queryService.myBlogs(user.id());
  }
}
