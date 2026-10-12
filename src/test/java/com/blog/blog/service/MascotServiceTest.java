package com.blog.blog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogVisibility;
import com.blog.blog.domain.JoinPolicy;
import com.blog.blog.domain.Mascot;
import com.blog.blog.repository.BlogRepository;
import com.blog.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 마스코트 저장과 비어 있는 블로그 찾기 (T148, BLG-14, D-116). 테스트에는 API 키가 없어 단어 사전으로 고른다. */
@IntegrationTest
class MascotServiceTest {

  @Autowired MascotService mascotService;
  @Autowired BlogService blogService;
  @Autowired BlogRepository blogRepository;
  @Autowired UserRepository userRepository;
  @Autowired JdbcTemplate jdbc;

  @Test
  void assignsFromTagsAndStoresLowercaseKey() {
    Blog blog = newBlog("민준의 식탁", List.of("집밥", "레시피"));
    assertThat(blogRepository.findIdsWithoutMascot(PageRequest.of(0, 10_000)))
        .contains(blog.getId());

    assertThat(mascotService.assign(blog.getId())).isEqualTo(Mascot.BEAR);

    assertThat(
            jdbc.queryForObject(
                "select mascot from blogs where id = ?", String.class, blog.getId()))
        .isEqualTo("bear");
    assertThat(blogRepository.findIdsWithoutMascot(PageRequest.of(0, 10_000)))
        .doesNotContain(blog.getId());
  }

  @Test
  void unknownStoredValueReadsAsNotChosen() {
    Blog blog = newBlog("이름", List.of());
    jdbc.update("update blogs set mascot = 'dragon' where id = ?", blog.getId());
    assertThat(blogRepository.findById(blog.getId()).orElseThrow().getMascot()).isNull();
  }

  @Test
  void closedBlogIsSkipped() {
    Blog blog = newBlog("닫힌 블로그", List.of("여행"));
    jdbc.update("update blogs set status = 'CLOSED' where id = ?", blog.getId());

    assertThat(mascotService.assign(blog.getId())).isNull();
    assertThat(blogRepository.findIdsWithoutMascot(PageRequest.of(0, 10_000)))
        .doesNotContain(blog.getId());
  }

  private Blog newBlog(String name, List<String> tags) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    User owner =
        userRepository.save(
            new User(
                "m" + unique + "@example.com",
                "hash",
                "이름",
                "m" + unique,
                "01012345678",
                LocalDateTime.now()));
    return blogService.create(
        owner.getId(),
        "m-" + unique,
        new BlogService.BlogForm(name, "소개", BlogVisibility.PUBLIC, JoinPolicy.OPEN, tags));
  }
}
