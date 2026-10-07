package com.blog.blog.repository;

import com.blog.blog.domain.BlogMember;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlogMemberRepository extends JpaRepository<BlogMember, Long> {

  /** 블로그별 역할은 토큰이 아니라 요청마다 이 조회로 확인한다 (SEC-07). */
  Optional<BlogMember> findByBlogIdAndUserId(Long blogId, Long userId);

  boolean existsByBlogIdAndUserId(Long blogId, Long userId);

  /** 내 블로그 목록 (BLG-06). */
  List<BlogMember> findByUserId(Long userId);
}
