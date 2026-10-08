package com.blog.board.api;

import com.blog.board.domain.Category;
import com.blog.board.service.CategoryService;
import com.blog.board.service.PostQueryService.CategoryRow;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 카테고리 관리 (T066, BRD-03). 블로그장 또는 블로그 정보 수정 권한 부블로그장. 목록은 PostController. */
@RestController
public class CategoryController {

  private final CategoryService categoryService;

  public CategoryController(CategoryService categoryService) {
    this.categoryService = categoryService;
  }

  public record NameForm(String name) {}

  public record OrderForm(List<Long> ids) {}

  @PostMapping("/api/blogs/{blogId}/categories")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'EDIT_INFO')")
  public CategoryRow create(@PathVariable Long blogId, @RequestBody NameForm form) {
    Category c = categoryService.create(blogId, form.name());
    return new CategoryRow(c.getId(), c.getName(), c.getSortOrder());
  }

  /** 순서 바꾸기. 그 블로그 카테고리 번호를 보일 순서대로 모두 보낸다 */
  @PutMapping("/api/blogs/{blogId}/categories")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'EDIT_INFO')")
  public ResponseEntity<Void> reorder(@PathVariable Long blogId, @RequestBody OrderForm form) {
    categoryService.reorder(blogId, form.ids());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/api/blogs/{blogId}/categories/{categoryId}")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'EDIT_INFO')")
  public CategoryRow rename(
      @PathVariable Long blogId, @PathVariable Long categoryId, @RequestBody NameForm form) {
    Category c = categoryService.rename(blogId, categoryId, form.name());
    return new CategoryRow(c.getId(), c.getName(), c.getSortOrder());
  }

  @DeleteMapping("/api/blogs/{blogId}/categories/{categoryId}")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'EDIT_INFO')")
  public ResponseEntity<Void> delete(@PathVariable Long blogId, @PathVariable Long categoryId) {
    categoryService.delete(blogId, categoryId);
    return ResponseEntity.noContent().build();
  }
}
