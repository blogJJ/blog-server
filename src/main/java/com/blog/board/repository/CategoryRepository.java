package com.blog.board.repository;

import com.blog.board.domain.Category;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

  List<Category> findByBlogIdOrderBySortOrderAscIdAsc(Long blogId);
}
