package com.blog.blog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogVisibility;
import com.blog.blog.domain.CoverScene;
import com.blog.blog.domain.JoinPolicy;
import com.blog.blog.domain.Mascot;
import com.blog.blog.domain.MascotDesign;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.repository.BlogTagRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 마스코트 예약 작업(빈 블로그 채우기·예전 장면 바꾸기·바탕 고르게 나누기)과 대체 규칙 (T148, T158, T169, BLG-14, D-116, D-118,
 * D-143). DB 없이 저장소를 흉내 내어 한 번 돌 때의 한도와 실패한 블로그를 건너뛰는지를 본다.
 */
class MascotScheduledJobTest {

  private final BlogRepository blogRepository = mock(BlogRepository.class);
  private final BlogTagRepository blogTagRepository = mock(BlogTagRepository.class);
  private final ClaudeMascotClient claude = mock(ClaudeMascotClient.class);
  private final CoverArtService coverArt = mock(CoverArtService.class);

  @Test
  @DisplayName("마스코트 기능을 끄면 예약 작업은 블로그를 읽지도 않는다 (app.mascot.enabled)")
  void disabledBackfillDoesNothing() {
    service(false).backfill();

    verifyNoInteractions(blogRepository, claude);
  }

  @Test
  @DisplayName("Claude가 고르지 못하면 단어 사전 후보로 채우고, 한 블로그가 실패해도 다음 블로그를 채운다 (BLG-14, D-116)")
  void backfillFallsBackToKeywordsAndSkipsFailures() {
    Blog blog = blog(2L, "민준의 식탁", null);
    when(blogRepository.findIdsWithoutMascot(PageRequest.of(0, 20))).thenReturn(List.of(1L, 2L));
    when(blogRepository.findById(1L)).thenThrow(new IllegalStateException("db down"));
    when(blogRepository.findById(2L)).thenReturn(Optional.of(blog));
    when(claude.choose(anyString(), eq(List.of()))).thenReturn(Optional.empty());

    service(true).backfill();

    verify(claude).choose("민준의 식탁", List.of());
    assertThat(blog.getMascot()).isNotNull();
    // 아무도 안 쓰면 후보의 첫째 (D-143)
    assertThat(blog.getMascot().base())
        .isEqualTo(KeywordMascotPicker.candidates("민준의 식탁", List.of()).get(0));
    assertThat(blog.getCoverScene()).isNotNull();
    assertThat(blog.getCoverScene().mixed()).isTrue();
  }

  @Test
  @DisplayName("예전 장면·운동 장면 다시 고르기는 실패한 블로그를 건너뛰고 나머지를 바꾼다 (T158)")
  void coverRefreshSkipsFailedBlogs() {
    Blog legacy = blog(2L, "이름", null);
    legacy.changeCoverScene(
        new CoverScene(
            CoverScene.Scene.STUDY,
            CoverScene.Time.NIGHT,
            CoverScene.Weather.RAIN,
            CoverScene.Palette.MINT,
            4821));
    when(blogRepository.findIdsWithLegacyCover(100)).thenReturn(List.of(1L, 2L));
    when(blogRepository.findById(1L)).thenThrow(new IllegalStateException("db down"));
    when(blogRepository.findById(2L)).thenReturn(Optional.of(legacy));

    assertThat(service(true).refreshLegacyCovers()).isEqualTo(1);
    assertThat(legacy.getCoverScene().mixed()).isTrue();

    Blog snow = blog(4L, "이름", null);
    snow.changeCoverScene(
        new CoverScene(
                CoverScene.Scene.SNOWFIELD,
                CoverScene.Time.DAY,
                CoverScene.Weather.CLEAR,
                CoverScene.Palette.WARM,
                9)
            .withLayers(null, null));
    when(blogRepository.findIdsForSportRefresh(anyString(), eq(0L), eq(100)))
        .thenReturn(List.of(3L, 4L));
    when(blogRepository.findById(3L)).thenThrow(new IllegalStateException("db down"));
    when(blogRepository.findById(4L)).thenReturn(Optional.of(snow));

    assertThat(service(true).refreshSportCovers()).isEqualTo(1);
    // 겨울 단어 없는 눈밭은 운동 장면으로 바꾼다
    assertThat(snow.getCoverScene().scene()).isIn(KeywordScenePicker.SPORT_POOL);
  }

  @Test
  @DisplayName("바탕 고르게 나누기는 한 번 돌 때 50개까지만 옮긴다 (D-143)")
  void rebalanceMovesAtMostFiftyPerRun() {
    int crowded = 120;
    List<Blog> blogs = new ArrayList<>();
    List<Long> ids = new ArrayList<>();
    for (long id = 1; id <= crowded; id++) {
      Blog b = blog(id, "이름", MascotDesign.ofVariant(Mascot.COMPUTER, (int) id));
      blogs.add(b);
      ids.add(id);
      when(blogRepository.findById(id)).thenReturn(Optional.of(b));
    }
    when(blogRepository.countMascotBases())
        .thenReturn(List.<Object[]>of(new Object[] {"computer", (long) crowded}));
    when(blogRepository.findOpenIdsByMascotBase("computer", 500)).thenReturn(ids);

    assertThat(service(true).rebalanceMascots()).isEqualTo(50);

    List<Mascot> bases = blogs.stream().map(b -> b.getMascot().base()).toList();
    assertThat(bases.stream().filter(m -> m != Mascot.COMPUTER))
        .hasSize(50)
        .allMatch(Mascot::animal);
    // 몫(120 ÷ 30 = 4)을 넘게 한 바탕으로 몰지 않는다
    long ceiling = MascotService.ceiling(crowded);
    for (Mascot m : Mascot.values()) {
      if (m != Mascot.COMPUTER) {
        assertThat(bases.stream().filter(b -> b == m).count()).isLessThanOrEqualTo(ceiling);
      }
    }
  }

  @Test
  @DisplayName("옮기는 중 실패했거나 그 사이 바탕이 바뀐 블로그는 건너뛰고, 옮길 동물 바탕이 모두 차 있으면 그대로 둔다 (D-143)")
  void rebalanceSkipsFailedChangedAndFullCases() {
    Blog changed = blog(2L, "이름", MascotDesign.plain(Mascot.CAT));
    Blog movable = blog(3L, "이름", MascotDesign.ofVariant(Mascot.COMPUTER, 3));
    when(blogRepository.countMascotBases())
        .thenReturn(List.<Object[]>of(new Object[] {"computer", 3L}));
    when(blogRepository.findOpenIdsByMascotBase("computer", 500)).thenReturn(List.of(1L, 2L, 3L));
    when(blogRepository.findById(1L)).thenThrow(new IllegalStateException("db down"));
    when(blogRepository.findById(2L)).thenReturn(Optional.of(changed));
    when(blogRepository.findById(3L)).thenReturn(Optional.of(movable));

    assertThat(service(true).rebalanceMascots()).isEqualTo(1);
    assertThat(changed.getMascot()).isEqualTo(MascotDesign.plain(Mascot.CAT));
    assertThat(movable.getMascot().base().animal()).isTrue();
    // 색·모자·장식은 그대로 두고 바탕만 바꾼다
    MascotDesign before = MascotDesign.ofVariant(Mascot.COMPUTER, 3);
    assertThat(movable.getMascot().tone()).isEqualTo(before.tone());
    assertThat(movable.getMascot().hat()).isEqualTo(before.hat());
    assertThat(movable.getMascot().charm()).isEqualTo(before.charm());
  }

  @Test
  @DisplayName("동물 바탕이 모두 몫만큼 차 있고 주제 단어도 없으면 사물 바탕으로 옮기지 않고 그대로 둔다 (D-143)")
  void rebalanceLeavesBlogWhenEveryAnimalIsFull() {
    Blog blog = blog(1L, "이름", MascotDesign.ofVariant(Mascot.COMPUTER, 1));
    List<Object[]> counts = new ArrayList<>();
    counts.add(new Object[] {"computer", 2L});
    Arrays.stream(Mascot.values())
        .filter(Mascot::animal)
        .forEach(m -> counts.add(new Object[] {m.key(), 1L}));
    when(blogRepository.countMascotBases()).thenReturn(counts);
    when(blogRepository.findOpenIdsByMascotBase("computer", 500)).thenReturn(List.of(1L));
    when(blogRepository.findById(1L)).thenReturn(Optional.of(blog));

    assertThat(service(true).rebalanceMascots()).isZero();
    assertThat(blog.getMascot()).isEqualTo(MascotDesign.ofVariant(Mascot.COMPUTER, 1));
  }

  @Test
  @DisplayName("예전 장면을 새로 고르면 지금과 다른 장면·다른 배치 번호가 된다. 소품 주제는 태그가 없으면 그대로 둔다 (T158, D-129)")
  void freshCoverNeverRepeatsTheCurrentSceneOrSeed() {
    CoverScene current =
        new CoverScene(
            CoverScene.Scene.KITCHEN,
            CoverScene.Time.DAY,
            CoverScene.Weather.CLEAR,
            CoverScene.Palette.WARM,
            12,
            CoverScene.Prop.FOOD);
    // 무작위가 처음에 지금 장면·지금 번호를 뽑아도 다시 뽑는다
    RandomGenerator random =
        scripted(0, CoverScene.Scene.KITCHEN.ordinal(), CoverScene.Scene.PARK.ordinal(), 12, 13);

    CoverScene fresh = MascotService.freshCover("이름", List.of(), current, random);

    assertThat(fresh.scene()).isEqualTo(CoverScene.Scene.PARK);
    assertThat(fresh.seed()).isEqualTo(13);
    assertThat(fresh.prop()).isEqualTo(CoverScene.Prop.FOOD);
    assertThat(fresh.mixed()).isTrue();
    assertThat(fresh.backdrop()).isNotNull();

    // 지금 장면이 없으면 비교할 것이 없고 소품 주제도 없다
    CoverScene first =
        MascotService.freshCover(
            "이름", List.of(), null, scripted(0, CoverScene.Scene.KITCHEN.ordinal(), 12));
    assertThat(first.scene()).isEqualTo(CoverScene.Scene.KITCHEN);
    assertThat(first.seed()).isEqualTo(12);
    assertThat(first.prop()).isNull();
    // 장면이 없는 블로그는 운동 장면 다시 고르기 대상이 아니다
    assertThat(MascotService.sportCover("이름", List.of("축구"), null, random)).isNull();
  }

  @Test
  @DisplayName("한 바탕의 조합이 모두 쓰이고 있으면 원래 조합을 그대로 쓴다 (D-118)")
  void uniqueKeepsWantedWhenEveryVariantIsTaken() {
    MascotDesign wanted = MascotDesign.ofVariant(Mascot.OWL, 7);
    Set<String> all = new HashSet<>();
    for (int i = 0; i < MascotDesign.VARIANTS; i++) {
      all.add(MascotDesign.ofVariant(Mascot.OWL, i).value());
    }

    assertThat(MascotService.unique(wanted, all)).isEqualTo(wanted);

    all.remove(MascotDesign.ofVariant(Mascot.OWL, 100).value());
    assertThat(MascotService.unique(wanted, all).value())
        .isEqualTo(MascotDesign.ofVariant(Mascot.OWL, 100).value());
  }

  @Test
  @DisplayName("대체 그림의 날씨는 절반쯤 맑고 나머지는 여러 날씨가 섞인다. 이름이 없어도 그린다 (D-129)")
  void fallbackWeatherIsAboutHalfClear() {
    List<CoverScene.Weather> weathers =
        IntStream.range(0, 400)
            .mapToObj(
                i -> MascotService.fallback("블로그" + i, List.of(), Mascot.CAT).cover().weather())
            .toList();
    long clear = weathers.stream().filter(w -> w == CoverScene.Weather.CLEAR).count();

    assertThat(clear).isBetween(160L, 300L);
    assertThat(new HashSet<>(weathers)).hasSizeGreaterThan(2);
    assertThat(MascotService.fallback(null, List.of(), Mascot.CAT).mascot().base())
        .isEqualTo(Mascot.CAT);
  }

  // ---------- 도우미 ----------

  private MascotService service(boolean enabled) {
    return new MascotService(
        blogRepository,
        blogTagRepository,
        claude,
        coverArt,
        mock(PlatformTransactionManager.class),
        enabled);
  }

  private static Blog blog(Long id, String name, MascotDesign mascot) {
    Blog blog = new Blog(null, "s-" + id, name, "", BlogVisibility.PUBLIC, JoinPolicy.OPEN);
    ReflectionTestUtils.setField(blog, "id", id);
    blog.changeMascot(mascot);
    return blog;
  }

  /** 정해 둔 순서대로 수를 내는 무작위. 다 쓰면 0, 참·거짓은 늘 참이다. */
  private static RandomGenerator scripted(int... values) {
    Deque<Integer> queue = new ArrayDeque<>();
    for (int v : values) {
      queue.add(v);
    }
    return new RandomGenerator() {
      @Override
      public long nextLong() {
        return 0;
      }

      @Override
      public int nextInt(int bound) {
        Integer next = queue.poll();
        return next == null ? 0 : Math.floorMod(next, bound);
      }

      @Override
      public boolean nextBoolean() {
        return true;
      }
    };
  }
}
