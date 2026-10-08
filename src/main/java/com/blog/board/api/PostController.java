package com.blog.board.api;

import com.blog.board.domain.Post;
import com.blog.board.service.PostQueryService;
import com.blog.board.service.PostQueryService.CategoryRow;
import com.blog.board.service.PostQueryService.PostDetail;
import com.blog.board.service.PostQueryService.PostList;
import com.blog.board.service.PostService;
import com.blog.board.service.PostService.PostForm;
import com.blog.board.service.ViewCountService;
import com.blog.common.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 글 쓰기·수정·삭제·목록·상세 (T063~T065, BRD-01~03, BRD-11). */
@RestController
public class PostController {

  private final PostService postService;
  private final PostQueryService queryService;
  private final ViewCountService viewCountService;

  public PostController(
      PostService postService, PostQueryService queryService, ViewCountService viewCountService) {
    this.postService = postService;
    this.queryService = queryService;
    this.viewCountService = viewCountService;
  }

  public record Saved(Long id) {}

  @PostMapping("/api/blogs/{blogId}/posts")
  @ResponseStatus(HttpStatus.CREATED)
  public Saved create(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @RequestBody PostForm form) {
    Post post = postService.create(blogId, user.id(), form);
    return new Saved(post.getId());
  }

  @PutMapping("/api/posts/{postId}")
  public Saved update(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long postId,
      @RequestBody PostForm form) {
    return new Saved(postService.update(postId, user.id(), form).getId());
  }

  @DeleteMapping("/api/posts/{postId}")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal AuthUser user, @PathVariable Long postId) {
    postService.delete(postId, user.id(), user.role());
    return ResponseEntity.noContent().build();
  }

  /** 블로그 글 목록. 일부 공개 블로그는 share 값을 같이 보낸다 */
  @GetMapping("/api/blogs/{blogId}/posts")
  public PostList list(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @RequestParam(required = false) Long category,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) String share) {
    return queryService.list(blogId, user == null ? null : user.id(), share, category, size, page);
  }

  /** 글 상세. 오늘 처음 보면 조회수가 오른다. 작성자 본인은 세지 않는다 */
  @GetMapping("/api/posts/{postId}")
  public PostDetail detail(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long postId,
      @RequestParam(required = false) String share,
      HttpServletRequest request) {
    Long viewerId = user == null ? null : user.id();
    PostDetail detail =
        queryService.detail(postId, viewerId, user == null ? null : user.role(), share);
    if (detail.canEdit()) {
      return detail; // 작성자가 자기 글을 열면 세지 않는다
    }
    String key =
        viewCountService.viewerKey(
            viewerId, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    if (viewCountService.record(postId, key)) {
      return withViewCount(detail, detail.viewCount() + 1);
    }
    return detail;
  }

  @GetMapping("/api/blogs/{blogId}/categories")
  public List<CategoryRow> categories(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @RequestParam(required = false) String share) {
    return queryService.categories(blogId, user == null ? null : user.id(), share);
  }

  private static PostDetail withViewCount(PostDetail d, int viewCount) {
    return new PostDetail(
        d.id(),
        d.title(),
        d.html(),
        d.markdown(),
        d.authorNickname(),
        d.categoryId(),
        d.categoryName(),
        d.notice(),
        d.tags(),
        viewCount,
        d.likeCount(),
        d.commentCount(),
        d.createdAt(),
        d.updatedAt(),
        d.blog(),
        d.canEdit(),
        d.canDelete(),
        d.liked());
  }
}
