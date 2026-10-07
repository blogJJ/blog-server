package com.blog.blog.repository;

import com.blog.blog.domain.BlogManagerPermission;
import com.blog.blog.domain.ManagerPermission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlogManagerPermissionRepository
    extends JpaRepository<BlogManagerPermission, Long> {

  List<BlogManagerPermission> findByBlogMemberId(Long blogMemberId);

  boolean existsByBlogMemberIdAndPermission(Long blogMemberId, ManagerPermission permission);
}
