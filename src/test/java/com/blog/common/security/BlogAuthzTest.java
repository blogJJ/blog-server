package com.blog.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogManagerPermission;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.BlogMemberRole;
import com.blog.blog.domain.BlogVisibility;
import com.blog.blog.domain.JoinPolicy;
import com.blog.blog.domain.ManagerPermission;
import com.blog.blog.repository.BlogManagerPermissionRepository;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.blog.repository.BlogRepository;
import com.blog.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 블로그별 역할을 진짜 DB로 확인하는지 (T017, SEC-07, SC-002 일부). 트랜잭션 없이 저장해 실제 요청처럼 새로 읽게 하고, 테스트마다 다른 이메일·주소를
 * 쓴다.
 */
@IntegrationTest
class BlogAuthzTest {

  @Autowired BlogAuthz blogAuthz;
  @Autowired UserRepository userRepository;
  @Autowired BlogRepository blogRepository;
  @Autowired BlogMemberRepository memberRepository;
  @Autowired BlogManagerPermissionRepository permissionRepository;
  @Autowired JdbcTemplate jdbc;
  @Autowired GuardedService guardedService;

  User owner;
  User manager;
  User member;
  User outsider;
  Blog blogA;
  Blog blogB;
  BlogMember managerRow;
  BlogMember memberRow;

  @BeforeEach
  void setUp() {
    owner = newUser("owner");
    manager = newUser("mgr");
    member = newUser("mem");
    outsider = newUser("out");
    blogA = newBlog(owner);
    blogB = newBlog(outsider);
    memberRepository.save(new BlogMember(blogA, owner, BlogMemberRole.OWNER));
    memberRepository.save(new BlogMember(blogB, outsider, BlogMemberRole.OWNER));
    managerRow = memberRepository.save(new BlogMember(blogA, manager, BlogMemberRole.MANAGER));
    memberRow = memberRepository.save(new BlogMember(blogA, member, BlogMemberRole.MEMBER));
    permissionRepository.save(
        new BlogManagerPermission(managerRow, ManagerPermission.MANAGE_MEMBERS));
  }

  @AfterEach
  void clearLogin() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void ownerManagesOwnBlogButNotAnother() {
    loginAs(owner);

    assertThat(blogAuthz.isOwner(blogA.getId())).isTrue();
    assertThat(blogAuthz.hasPermission(blogA.getId(), ManagerPermission.MANAGE_POSTS)).isTrue();
    assertThat(blogAuthz.isOwner(blogB.getId())).isFalse();
    assertThat(blogAuthz.isMember(blogB.getId())).isFalse();
    assertThat(blogAuthz.hasPermission(blogB.getId(), "EDIT_INFO")).isFalse();
  }

  @Test
  void managerHasOnlyGrantedPermissions() {
    loginAs(manager);

    assertThat(blogAuthz.isOwner(blogA.getId())).isFalse();
    assertThat(blogAuthz.hasPermission(blogA.getId(), "MANAGE_MEMBERS")).isTrue();
    assertThat(blogAuthz.hasPermission(blogA.getId(), "MANAGE_POSTS")).isFalse();
    assertThat(blogAuthz.canHandleReports(blogA.getId())).isTrue();
  }

  @Test
  void memberIsMemberOnly() {
    loginAs(member);

    assertThat(blogAuthz.isMember(blogA.getId())).isTrue();
    assertThat(blogAuthz.hasPermission(blogA.getId(), "EDIT_INFO")).isFalse();
  }

  @Test
  void anonymousHasNoRole() {
    assertThat(blogAuthz.isMember(blogA.getId())).isFalse();
    assertThat(blogAuthz.isOwner(blogA.getId())).isFalse();
  }

  @Test
  void memberSuspendedInBlogCannotEnter() {
    jdbc.update(
        "update blog_members set suspended_until = ? where id = ?",
        LocalDateTime.now().plusDays(7),
        memberRow.getId());
    loginAs(member);

    assertThat(blogAuthz.isMember(blogA.getId())).isFalse();
  }

  @Test
  void accountSuspendedOwnerCannotManageAndManagersTakeOver() {
    jdbc.update(
        "update users set suspended_until = ? where id = ?",
        LocalDateTime.now().plusDays(3),
        owner.getId());

    loginAs(owner);
    assertThat(blogAuthz.isMember(blogA.getId())).isTrue();
    assertThat(blogAuthz.isOwner(blogA.getId())).isFalse();
    assertThat(blogAuthz.hasPermission(blogA.getId(), "EDIT_INFO")).isFalse();

    // 정지 동안 부블로그장이 신고 처리를 맡는다 (D-114)
    loginAs(manager);
    assertThat(blogAuthz.hasPermission(blogA.getId(), "MANAGE_MEMBERS")).isTrue();
    assertThat(blogAuthz.canHandleReports(blogA.getId())).isTrue();
    assertThat(blogAuthz.hasPermission(blogA.getId(), "MANAGE_POSTS")).isFalse();
  }

  @Test
  void managerWithoutMemberPermissionGetsItOnlyWhileOwnerSuspended() {
    User other = newUser("mg2");
    memberRepository.save(new BlogMember(blogA, other, BlogMemberRole.MANAGER));
    loginAs(other);
    assertThat(blogAuthz.hasPermission(blogA.getId(), "MANAGE_MEMBERS")).isFalse();
    assertThat(blogAuthz.canHandleReports(blogA.getId())).isFalse();

    jdbc.update(
        "update users set suspended_until = ? where id = ?",
        LocalDateTime.now().plusDays(3),
        owner.getId());
    assertThat(blogAuthz.hasPermission(blogA.getId(), "MANAGE_MEMBERS")).isTrue();
    assertThat(blogAuthz.canHandleReports(blogA.getId())).isTrue();
    assertThat(blogAuthz.isOwner(blogA.getId())).isFalse();
    assertThat(blogAuthz.hasPermission(blogA.getId(), "EDIT_INFO")).isFalse();

    // 정지가 끝나면 원래 권한으로 돌아간다
    jdbc.update(
        "update users set suspended_until = ? where id = ?",
        LocalDateTime.now().minusMinutes(1),
        owner.getId());
    assertThat(blogAuthz.hasPermission(blogA.getId(), "MANAGE_MEMBERS")).isFalse();

    // 일반 멤버는 블로그장이 정지돼도 권한이 없다
    jdbc.update(
        "update users set suspended_until = ? where id = ?",
        LocalDateTime.now().plusDays(3),
        owner.getId());
    loginAs(member);
    assertThat(blogAuthz.canHandleReports(blogA.getId())).isFalse();
  }

  @Test
  void closedBlogGivesNoRole() {
    jdbc.update("update blogs set status = 'CLOSED' where id = ?", blogA.getId());
    loginAs(owner);

    assertThat(blogAuthz.isOwner(blogA.getId())).isFalse();
    assertThat(blogAuthz.isMember(blogA.getId())).isFalse();
  }

  @Test
  void preAuthorizeUsesBlogAuthz() {
    loginAs(owner);
    assertThat(guardedService.closeBlog(blogA.getId())).isEqualTo("closed");

    assertThatThrownBy(() -> guardedService.closeBlog(blogB.getId()))
        .isInstanceOf(AccessDeniedException.class);
  }

  private void loginAs(User user) {
    AuthUser principal = new AuthUser(user.getId(), UserRole.USER);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
  }

  private User newUser(String prefix) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    return userRepository.save(
        new User(
            prefix + unique + "@example.com",
            "hash",
            "이름",
            prefix.substring(0, 2) + unique,
            "01012345678",
            LocalDateTime.now()));
  }

  private Blog newBlog(User owner) {
    String slug = "b-" + UUID.randomUUID().toString().substring(0, 8);
    return blogRepository.save(
        new Blog(owner, slug, "블로그", null, BlogVisibility.PUBLIC, JoinPolicy.OPEN));
  }

  /** {@code @PreAuthorize}에서 실제로 쓰는 모양을 확인하는 서비스. */
  static class GuardedService {
    @PreAuthorize("@blogAuthz.isOwner(#blogId)")
    public String closeBlog(Long blogId) {
      return "closed";
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    GuardedService guardedService() {
      return new GuardedService();
    }
  }
}
