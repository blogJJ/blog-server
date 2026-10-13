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
import java.util.ArrayList;
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
    while (mascotService.refreshLegacyCovers() > 0) {
      // 한 번에 한 묶음씩 고치므로 남은 것이 없을 때까지 다시 부른다
    }

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

    while (mascotService.refreshLegacyCovers() > 0) {
      // 한 번에 한 묶음씩 고치므로 남은 것이 없을 때까지 다시 부른다
    }

    CoverScene cover = CoverScene.parse(coverOf(blog.getId())).orElseThrow();
    assertThat(cover.mixed()).isTrue();
    assertThat(cover.backdrop()).isNotNull();
    assertThat(cover.scene()).isNotEqualTo(CoverScene.Scene.KITCHEN);
    // 태그에 주제가 없으면 지금 소품 주제를 그대로 둔다
    assertThat(cover.prop()).isEqualTo(CoverScene.Prop.FOOD);
  }

  @Test
  void sportBlogsAreRepickedOnceWithNewSportScenes() {
    // T158 후속: 운동 태그가 있는 블로그와 겨울 단어 없는 눈밭을 한 번 다시 고른다. 마스코트와 대표 이미지는 그대로다
    Blog soccer = newBlog("이름", List.of("축구"));
    Blog generic = newBlog("이름", List.of("운동", "일상"));
    Blog gym = newBlog("이름", List.of("헬스"));
    Blog ski = newBlog("이름", List.of("스키", "운동"));
    Blog plainSnow = newBlog("이름", List.of());
    Blog other = newBlog("이름", List.of("요리"));
    Blog closed = newBlog("이름", List.of("수영"));
    Blog mlBlog = newBlog("이름", List.of("머신러닝"));
    jdbc.update(
        "update blogs set mascot = 'cat.mint.beret.heart', cover_image = '/uploads/s.png',"
            + " cover_scene = 'snowfield.day.clear.mint.12.-.sea~bloom' where id = ?",
        soccer.getId());
    jdbc.update(
        "update blogs set cover_scene = 'library.night.clear.warm.7.-.city~' where id = ?",
        generic.getId());
    jdbc.update(
        "update blogs set cover_scene = 'gym.day.clear.warm.3.-.~' where id = ?", gym.getId());
    jdbc.update(
        "update blogs set cover_scene = 'snowfield.day.snow.mint.5.-.snow~frost' where id = ?",
        ski.getId());
    jdbc.update(
        "update blogs set cover_scene = 'snowfield.dawn.clear.ocean.9.-.~' where id = ?",
        plainSnow.getId());
    jdbc.update(
        "update blogs set cover_scene = 'kitchen.day.clear.warm.1.-.~' where id = ?",
        other.getId());
    jdbc.update(
        "update blogs set cover_scene = 'snowfield.day.clear.warm.1.-.~', status = 'CLOSED'"
            + " where id = ?",
        closed.getId());
    jdbc.update(
        "update blogs set cover_scene = 'desk.day.clear.warm.2.-.~' where id = ?", mlBlog.getId());

    assertThat(mascotService.refreshSportCovers()).isGreaterThanOrEqualTo(3);

    assertThat(coverOf(soccer.getId())).isEqualTo("soccer.day.clear.mint.12.-.sea~bloom");
    assertThat(
            jdbc.queryForObject(
                "select concat(mascot, ' ', cover_image) from blogs where id = ?",
                String.class,
                soccer.getId()))
        .isEqualTo("cat.mint.beret.heart /uploads/s.png");
    CoverScene g = CoverScene.parse(coverOf(generic.getId())).orElseThrow();
    assertThat(g.scene()).isIn(KeywordScenePicker.SPORT_POOL);
    assertThat(g.value()).endsWith(".night.clear.warm.7.-.city~");
    assertThat(coverOf(gym.getId())).isEqualTo("gym.day.clear.warm.3.-.~");
    assertThat(coverOf(ski.getId())).isEqualTo("snowfield.day.snow.mint.5.-.snow~frost");
    assertThat(CoverScene.parse(coverOf(plainSnow.getId())).orElseThrow().scene())
        .isIn(KeywordScenePicker.SPORT_POOL);
    assertThat(coverOf(other.getId())).isEqualTo("kitchen.day.clear.warm.1.-.~");
    assertThat(coverOf(closed.getId())).isEqualTo("snowfield.day.clear.warm.1.-.~");
    assertThat(coverOf(mlBlog.getId())).isEqualTo("desk.day.clear.warm.2.-.~");

    // 한 번 바꾼 값은 다시 고르지 않는다
    List<String> before =
        List.of(soccer, generic, gym, ski, plainSnow, other, closed, mlBlog).stream()
            .map(b -> coverOf(b.getId()))
            .toList();
    mascotService.refreshSportCovers();
    assertThat(
            List.of(soccer, generic, gym, ski, plainSnow, other, closed, mlBlog).stream()
                .map(b -> coverOf(b.getId()))
                .toList())
        .isEqualTo(before);
  }

  @Test
  void overusedBaseIsSpreadOnceWithoutTouchingCovers() {
    // T169(D-143): 한 바탕에 몰린 블로그를 덜 쓰는 바탕으로 옮긴다. 장면과 대표 이미지는 그대로다
    List<Blog> blogs = new ArrayList<>();
    for (int i = 0; i < 35; i++) {
      blogs.add(newBlog("이름", i == 0 ? List.of("반려식물") : List.of()));
    }
    for (int i = 0; i < blogs.size(); i++) {
      jdbc.update(
          "update blogs set mascot = ?, cover_image = '/uploads/r.png',"
              + " cover_scene = 'desk.day.clear.warm.3.-.~' where id = ?",
          MascotDesign.ofVariant(Mascot.COMPUTER, i).value(),
          blogs.get(i).getId());
    }

    while (mascotService.rebalanceMascots() > 0) {
      // 한 번에 한 묶음씩 고치므로 남은 것이 없을 때까지 다시 부른다
    }

    List<String> after = blogs.stream().map(b -> mascotOf(b.getId())).toList();
    long stillComputer = after.stream().filter(m -> m.startsWith("computer.")).count();
    assertThat(stillComputer).isLessThan(blogs.size());
    // 단어 사전에 맞는 바탕(식물)이 비어 있으면 먼저 그리로, 아니면 동물로 옮긴다
    assertThat(
            after.stream()
                .filter(m -> !m.startsWith("computer."))
                .map(m -> MascotDesign.parse(m).orElseThrow().base()))
        .allMatch(m -> m.animal() || m == Mascot.PLANT);
    assertThat(after).doesNotHaveDuplicates();
    for (Blog b : blogs) {
      assertThat(
              jdbc.queryForObject(
                  "select concat(cover_scene, ' ', cover_image) from blogs where id = ?",
                  String.class,
                  b.getId()))
          .isEqualTo("desk.day.clear.warm.3.-.~ /uploads/r.png");
    }

    // 다시 돌려도 바뀌지 않는다
    assertThat(mascotService.rebalanceMascots()).isZero();
    assertThat(blogs.stream().map(b -> mascotOf(b.getId())).toList()).isEqualTo(after);
  }

  @Test
  void newBlogPrefersLeastUsedMatchingBase() {
    // D-143: '수영'은 펭귄과 고래에 맞는다. 둘 중 덜 쓰는 바탕을 쓴다
    Blog crowded = newBlog("이름", List.of());
    Blog crowded2 = newBlog("이름", List.of());
    jdbc.update("update blogs set mascot = 'penguin.mint.none.none' where id = ?", crowded.getId());
    jdbc.update("update blogs set mascot = 'penguin.sky.none.none' where id = ?", crowded2.getId());
    Integer penguins =
        jdbc.queryForObject(
            "select count(*) from blogs where mascot like 'penguin.%' and status <> 'CLOSED'",
            Integer.class);
    Integer whales =
        jdbc.queryForObject(
            "select count(*) from blogs where mascot like 'whale.%' and status <> 'CLOSED'",
            Integer.class);

    Blog swim = newBlog("물개", List.of("수영"));
    Mascot base = mascotService.assign(swim.getId()).base();

    assertThat(base).isIn(Mascot.PENGUIN, Mascot.WHALE);
    assertThat(base).isEqualTo(whales < penguins ? Mascot.WHALE : Mascot.PENGUIN);
  }

  private String mascotOf(Long blogId) {
    return jdbc.queryForObject("select mascot from blogs where id = ?", String.class, blogId);
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
