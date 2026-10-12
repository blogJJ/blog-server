package com.blog.blog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogVisibility;
import com.blog.blog.domain.CoverScene;
import com.blog.blog.domain.JoinPolicy;
import com.blog.blog.domain.Mascot;
import com.blog.blog.domain.MascotDesign;
import com.blog.blog.repository.BlogRepository;
import com.blog.support.IntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 마스코트 저장, 겹치지 않는 조합, 비어 있는 블로그 찾기 (T148, BLG-14, D-116, D-118). 테스트에는 API 키가 없어 단어 사전으로 고른다. */
@IntegrationTest
class MascotServiceTest {

  @Autowired MascotService mascotService;
  @Autowired BlogService blogService;
  @Autowired BlogRepository blogRepository;
  @Autowired UserRepository userRepository;
  @Autowired JdbcTemplate jdbc;

  @Test
  void assignsFromTagsAndStoresCombination() {
    Blog blog = newBlog("민준의 식탁", List.of("집밥", "레시피"));
    assertThat(blogRepository.findIdsWithoutMascot(PageRequest.of(0, 10_000)))
        .contains(blog.getId());

    MascotDesign design = mascotService.assign(blog.getId());

    assertThat(design.base()).isEqualTo(Mascot.BEAR);
    assertThat(
            jdbc.queryForObject(
                "select mascot from blogs where id = ?", String.class, blog.getId()))
        .isEqualTo(design.value())
        .matches("bear\\.[a-z]+\\.[a-z]+\\.[a-z]+");
    assertThat(
            jdbc.queryForObject(
                "select cover_scene from blogs where id = ?", String.class, blog.getId()))
        // 장면은 '집밥', 소품 주제는 '레시피'에서 고르고, 일곱째 칸(배경~계절)이 붙는 섞기 모드다 (D-129)
        .matches("kitchen\\.[a-z]+\\.[a-z]+\\.[a-z]+\\.\\d+\\.food\\.[a-z]*~[a-z]*");
    assertThat(blogRepository.findIdsWithoutMascot(PageRequest.of(0, 10_000)))
        .doesNotContain(blog.getId());
  }

  @Test
  void blogWithoutCoverSceneIsFilledAgain() {
    Blog blog = newBlog("이름", List.of());
    mascotService.assign(blog.getId());
    jdbc.update("update blogs set cover_scene = null where id = ?", blog.getId());
    assertThat(blogRepository.findIdsWithoutMascot(PageRequest.of(0, 10_000)))
        .contains(blog.getId());
  }

  @Test
  void sameNameAndTagsStillGetDifferentPictures() {
    Blog first = newBlog("같은 이름", List.of("집밥"));
    Blog second = newBlog("같은 이름", List.of("집밥"));

    MascotDesign a = mascotService.assign(first.getId());
    MascotDesign b = mascotService.assign(second.getId());

    assertThat(b.base()).isEqualTo(a.base());
    assertThat(b.value()).isNotEqualTo(a.value());
  }

  @Test
  void reassigningGivesANewPicture() {
    Blog blog = newBlog("민준의 식탁", List.of("집밥"));
    MascotDesign before = mascotService.assign(blog.getId());

    String coverBefore =
        jdbc.queryForObject(
            "select cover_scene from blogs where id = ?", String.class, blog.getId());

    assertThat(mascotService.assign(blog.getId()).value()).isNotEqualTo(before.value());
    assertThat(
            jdbc.queryForObject(
                "select cover_scene from blogs where id = ?", String.class, blog.getId()))
        .isNotEqualTo(coverBefore);
  }

  @Test
  void legacyBaseOnlyValueStillReads() {
    Blog blog = newBlog("이름", List.of());
    jdbc.update("update blogs set mascot = 'cat' where id = ?", blog.getId());
    assertThat(blogRepository.findById(blog.getId()).orElseThrow().getMascot())
        .isEqualTo(MascotDesign.plain(Mascot.CAT));
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

  @Test
  void legacyCoverIsReplacedOnceWithMixedCover() {
    // T158(D-129 확장): 섞기 모드가 아닌 예전 장면은 예약 작업이 한 번 새로 고른다. 마스코트와 대표 이미지는 그대로다
    Blog legacy = newBlog("이름", List.of("화산"));
    Blog mixed = newBlog("이름", List.of());
    Blog closed = newBlog("이름", List.of());
    jdbc.update(
        "update blogs set mascot = 'cat.mint.beret.heart', cover_image = '/uploads/a.png',"
            + " cover_scene = 'study.night.rain.mint.4821' where id = ?",
        legacy.getId());
    jdbc.update(
        "update blogs set cover_scene = 'park.day.clear.warm.7.-.sea~bloom' where id = ?",
        mixed.getId());
    jdbc.update(
        "update blogs set cover_scene = 'study.day.clear.warm.1', status = 'CLOSED' where id = ?",
        closed.getId());

    // 다른 테스트가 남긴 블로그가 있어도 모두 지나갈 때까지 돈다
    while (mascotService.refreshLegacyCovers() > 0) {}

    String cover = coverOf(legacy.getId());
    // 장면을 고르는 단어가 없어 지금과 다른 장면, 배경은 태그('화산')에서 고른다
    assertThat(cover).matches("[a-z-]+\\.[a-z]+\\.[a-z]+\\.[a-z]+\\.\\d+\\.[a-z-]+\\.lava~[a-z]*");
    assertThat(cover).doesNotStartWith("study.");
    assertThat(
            jdbc.queryForObject(
                "select mascot from blogs where id = ?", String.class, legacy.getId()))
        .isEqualTo("cat.mint.beret.heart");
    assertThat(
            jdbc.queryForObject(
                "select cover_image from blogs where id = ?", String.class, legacy.getId()))
        .isEqualTo("/uploads/a.png");
    assertThat(coverOf(mixed.getId())).isEqualTo("park.day.clear.warm.7.-.sea~bloom");
    assertThat(coverOf(closed.getId())).isEqualTo("study.day.clear.warm.1");
    assertThat(blogRepository.findIdsWithLegacyCover(10_000))
        .doesNotContain(legacy.getId(), mixed.getId(), closed.getId());

    // 한 번 바꾼 값은 다시 고르지 않는다
    mascotService.refreshLegacyCovers();
    assertThat(coverOf(legacy.getId())).isEqualTo(cover);
  }

  @Test
  void legacyCoverWithoutTagsAlwaysGetsABackdrop() {
    Blog blog = newBlog("이름", List.of());
    jdbc.update(
        "update blogs set cover_scene = 'kitchen.day.clear.warm.12.food' where id = ?",
        blog.getId());

    while (mascotService.refreshLegacyCovers() > 0) {}

    CoverScene cover = CoverScene.parse(coverOf(blog.getId())).orElseThrow();
    assertThat(cover.mixed()).isTrue();
    assertThat(cover.backdrop()).isNotNull();
    assertThat(cover.scene()).isNotEqualTo(CoverScene.Scene.KITCHEN);
    // 태그에 주제가 없으면 지금 소품 주제를 그대로 둔다
    assertThat(cover.prop()).isEqualTo(CoverScene.Prop.FOOD);
  }

  private String coverOf(Long blogId) {
    return jdbc.queryForObject("select cover_scene from blogs where id = ?", String.class, blogId);
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
