package com.blog.blog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogManagerPermission;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.BlogMemberRole;
import com.blog.blog.domain.BlogVisibility;
import com.blog.blog.domain.JoinPolicy;
import com.blog.blog.domain.ManagerPermission;
import com.blog.support.IntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** 엔티티가 V1 테이블에 저장·조회되는지 진짜 MySQL로 확인한다 (T010~T014). */
@IntegrationTest
@Transactional
class BlogMemberRepositoryTest {

  @Autowired UserRepository userRepository;
  @Autowired BlogRepository blogRepository;
  @Autowired BlogMemberRepository blogMemberRepository;
  @Autowired BlogManagerPermissionRepository permissionRepository;

  @Test
  void savesUserBlogMemberAndPermission() {
    User owner = userRepository.save(user("Owner@Example.com", "owner"));
    User manager = userRepository.save(user("manager@example.com", "manager"));
    Blog blog =
        blogRepository.save(
            new Blog(owner, "daily-cook", "집밥 일기", "소개", BlogVisibility.PUBLIC, JoinPolicy.OPEN));
    blogMemberRepository.save(new BlogMember(blog, owner, BlogMemberRole.OWNER));
    BlogMember managerRow =
        blogMemberRepository.save(new BlogMember(blog, manager, BlogMemberRole.MANAGER));
    permissionRepository.save(
        new BlogManagerPermission(managerRow, ManagerPermission.MANAGE_MEMBERS));

    assertThat(userRepository.findByEmail("owner@example.com")).isPresent();
    assertThat(userRepository.existsByNickname("owner")).isTrue();
    assertThat(blogRepository.findBySlug("daily-cook"))
        .get()
        .extracting(Blog::getMemberCount)
        .isEqualTo(1);
    assertThat(blogMemberRepository.findByBlogIdAndUserId(blog.getId(), owner.getId()))
        .get()
        .satisfies(m -> assertThat(m.isOwner()).isTrue());
    assertThat(
            permissionRepository.existsByBlogMemberIdAndPermission(
                managerRow.getId(), ManagerPermission.MANAGE_MEMBERS))
        .isTrue();
    assertThat(
            permissionRepository.existsByBlogMemberIdAndPermission(
                managerRow.getId(), ManagerPermission.MANAGE_POSTS))
        .isFalse();
    assertThat(owner.getCreatedAt()).isNotNull();
  }

  @Test
  void emailIsStoredLowercase() {
    User saved = userRepository.save(user("MiXeD@Example.COM", "mixed"));

    assertThat(saved.getEmail()).isEqualTo("mixed@example.com");
  }

  private static User user(String email, String nickname) {
    return new User(
        email, "$2a$10$hash", "이름", nickname, "01012345678", LocalDateTime.of(2026, 10, 7, 9, 0));
  }
}
