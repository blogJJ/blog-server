package com.blog.board.service;

import com.blog.blog.domain.Blog;
import com.blog.blog.repository.BlogRepository;
import com.blog.board.domain.Category;
import com.blog.board.repository.CategoryRepository;
import com.blog.board.repository.PostRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카테고리 관리 (T066, BRD-03). 권한 확인(블로그장 또는 EDIT_INFO 부블로그장)은 컨트롤러의 {@code @PreAuthorize}가 한다. 이름은
 * 1~20자, 블로그 안에서 겹치지 않고, 블로그 하나에 30개까지. 지우면 그 카테고리 글은 "카테고리 없음"이 된다.
 */
@Service
public class CategoryService {

  public static final int NAME_MAX = 20;
  public static final int MAX_PER_BLOG = 30;

  private final CategoryRepository categoryRepository;
  private final BlogRepository blogRepository;
  private final PostRepository postRepository;

  public CategoryService(
      CategoryRepository categoryRepository,
      BlogRepository blogRepository,
      PostRepository postRepository) {
    this.categoryRepository = categoryRepository;
    this.blogRepository = blogRepository;
    this.postRepository = postRepository;
  }

  /** 맨 뒤에 추가한다. */
  @Transactional
  public Category create(Long blogId, String rawName) {
    Blog blog =
        blogRepository
            .findById(blogId)
            .filter(Blog::isOpen)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    List<Category> existing = categoryRepository.findByBlogIdOrderBySortOrderAscIdAsc(blogId);
    if (existing.size() >= MAX_PER_BLOG) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "카테고리는 30개까지 만들 수 있어요.");
    }
    String name = validate(rawName);
    checkDuplicate(existing, name, null);
    int last = existing.stream().mapToInt(Category::getSortOrder).max().orElse(-1);
    return categoryRepository.save(new Category(blog, name, last + 1));
  }

  @Transactional
  public Category rename(Long blogId, Long categoryId, String rawName) {
    Category category = find(blogId, categoryId);
    String name = validate(rawName);
    checkDuplicate(
        categoryRepository.findByBlogIdOrderBySortOrderAscIdAsc(blogId), name, categoryId);
    category.rename(name);
    return category;
  }

  /** 화면에서 끌어 놓은 순서대로 번호를 다시 매긴다. 그 블로그의 카테고리를 빠짐없이 보내야 한다. */
  @Transactional
  public void reorder(Long blogId, List<Long> ids) {
    List<Category> all = categoryRepository.findByBlogIdOrderBySortOrderAscIdAsc(blogId);
    Map<Long, Category> byId =
        all.stream().collect(Collectors.toMap(Category::getId, Function.identity()));
    if (ids == null || ids.size() != all.size() || !byId.keySet().equals(new HashSet<>(ids))) {
      throw new BusinessException(ErrorCode.CONFLICT, "카테고리 목록이 바뀌었어요. 새로고침한 뒤 다시 해 주세요.");
    }
    for (int i = 0; i < ids.size(); i++) {
      byId.get(ids.get(i)).changeSortOrder(i);
    }
  }

  @Transactional
  public void delete(Long blogId, Long categoryId) {
    Category category = find(blogId, categoryId);
    postRepository.clearCategory(categoryId);
    categoryRepository.delete(category);
  }

  private Category find(Long blogId, Long categoryId) {
    return categoryRepository
        .findById(categoryId)
        .filter(c -> c.getBlog().getId().equals(blogId))
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static void checkDuplicate(List<Category> existing, String name, Long selfId) {
    boolean taken =
        existing.stream()
            .anyMatch(c -> !c.getId().equals(selfId) && c.getName().equalsIgnoreCase(name));
    if (taken) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 있는 카테고리 이름이에요.");
    }
  }

  private static String validate(String raw) {
    String name = raw == null ? "" : raw.strip();
    int length = name.codePointCount(0, name.length());
    if (length < 1 || length > NAME_MAX) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "카테고리 이름은 1~20자로 입력해 주세요.");
    }
    return name;
  }
}
