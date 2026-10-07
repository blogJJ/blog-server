package com.blog.blog.repository;

import com.blog.blog.domain.Blog;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlogRepository extends JpaRepository<Blog, Long> {

  Optional<Blog> findBySlug(String slug);

  Optional<Blog> findByShareKey(String shareKey);

  boolean existsBySlug(String slug);
}
