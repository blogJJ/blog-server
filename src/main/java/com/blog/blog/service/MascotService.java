package com.blog.blog.service;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogTag;
import com.blog.blog.domain.CoverScene;
import com.blog.blog.domain.CoverScene.Backdrop;
import com.blog.blog.domain.CoverScene.Event;
import com.blog.blog.domain.CoverScene.Scene;
import com.blog.blog.domain.Mascot;
import com.blog.blog.domain.MascotDesign;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.repository.BlogTagRepository;
import jakarta.annotation.PreDestroy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 블로그 마스코트를 만든다 (T148, BLG-14, D-116, D-118). 블로그를 만들거나 이름·태그를 고치면 저장이 끝난 뒤 따로 만들고(블로그 저장은 기다리지
 * 않음), 아직 없는 블로그는 예약 작업이 조금씩 채운다. Claude가 바탕 그림·색·모자·장식을 고르고, 못 고르면 태그 단어 사전으로 바탕을 고른 뒤 나머지는 이름과
 * 태그로 정한다. 다른 블로그나 이 블로그의 지금 그림과 같은 조합이면 다른 조합으로 바꾼다. 대표 이미지가 없을 때 보여 줄 픽셀 장면(BLG-15, D-119)도 같이
 * 고르고, 배치 번호는 저장할 때마다 새로 뽑아 같은 장면이라도 블로그마다·태그를 바꿀 때마다 그림이 달라진다.
 */
@Service
public class MascotService {

  /**
   * 블로그 이름이나 태그가 바뀌었다. 커밋 뒤에 마스코트를 다시 고른다.
   *
   * @param created 새로 만든 블로그면 true. 이때만 대표 그림도 그린다 (T155, D-126)
   */
  public record BlogInfoChanged(Long blogId, boolean created) {}

  private static final Logger log = LoggerFactory.getLogger(MascotService.class);
  private static final int BACKFILL_BATCH = 20;
  // 겹칠 때 다음 조합으로 건너뛰는 폭. 조합 수(288)와 서로소라 모든 조합을 한 번씩 돈다
  private static final int STEP = 115;

  private final BlogRepository blogRepository;
  private final BlogTagRepository blogTagRepository;
  private final ClaudeMascotClient claude;
  private final CoverArtService coverArt;
  private final TransactionTemplate tx;
  private final boolean enabled;
  // Claude 호출이 몰리지 않게 한 번에 하나씩 고른다. 새 블로그의 대표 그림(CoverArtService)도 여기서 그린다
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "mascot-picker");
            t.setDaemon(true);
            return t;
          });

  public MascotService(
      BlogRepository blogRepository,
      BlogTagRepository blogTagRepository,
      ClaudeMascotClient claude,
      CoverArtService coverArt,
      PlatformTransactionManager transactionManager,
      @Value("${app.mascot.enabled:true}") boolean enabled) {
    this.blogRepository = blogRepository;
    this.blogTagRepository = blogTagRepository;
    this.claude = claude;
    this.coverArt = coverArt;
    this.tx = new TransactionTemplate(transactionManager);
    this.enabled = enabled;
  }

  @TransactionalEventListener
  public void onBlogInfoChanged(BlogInfoChanged event) {
    if (enabled) {
      worker.execute(
          () -> {
            assignSafely(event.blogId());
            // 마스코트와 픽셀 장면을 먼저 저장해 두고 오래 걸리는 그림은 그 뒤에 그린다
            if (event.created()) {
              coverArt.generateSafely(event.blogId());
            }
          });
    }
  }

  /** 마스코트나 장면이 없는 블로그를 채운다. 기존 블로그와, 고르다 서버가 꺼진 블로그용. */
  @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT10M")
  @SchedulerLock(name = "mascotBackfill", lockAtMostFor = "PT9M")
  public void backfill() {
    if (!enabled) {
      return;
    }
    List<Long> ids = blogRepository.findIdsWithoutMascot(PageRequest.of(0, BACKFILL_BATCH));
    ids.forEach(this::assignSafely);
  }

  /**
   * 블로그 하나의 마스코트를 만들고 저장한다. Claude 호출은 트랜잭션 밖에서 한다.
   *
   * @return 만든 조합. 블로그가 없거나 폐쇄됐으면 null
   */
  public MascotDesign assign(Long blogId) {
    Info info =
        tx.execute(
            s ->
                blogRepository
                    .findById(blogId)
                    .filter(Blog::isOpen)
                    .map(
                        b ->
                            new Info(
                                b.getName(), tagNames(b.getId()), b.getMascot(), b.getCoverScene()))
                    .orElse(null));
    if (info == null) {
      return null;
    }
    ClaudeMascotClient.Art art =
        claude.choose(info.name(), info.tags()).orElseGet(() -> fallback(info.name(), info.tags()));
    MascotDesign wanted = art.mascot();
    CoverScene cover =
        withLayers(
            withNewSeed(art.cover(), info.cover()),
            info.name(),
            info.tags(),
            ThreadLocalRandom.current());
    // 한 번에 하나씩 만들기 때문에(worker, ShedLock) 읽고 저장하는 사이에 같은 조합이 끼어들지 않는다
    MascotDesign mascot =
        tx.execute(
            s -> {
              Set<String> taken =
                  new HashSet<>(blogRepository.findMascotsInUse(wanted.base().key(), blogId));
              if (info.current() != null) {
                taken.add(info.current().value()); // 태그를 바꾸면 새 그림
              }
              MascotDesign design = unique(wanted, taken);
              blogRepository
                  .findById(blogId)
                  .ifPresent(
                      b -> {
                        b.changeMascot(design);
                        b.changeCoverScene(cover);
                      });
              return design;
            });
    return mascot;
  }

  private void assignSafely(Long blogId) {
    try {
      assign(blogId);
    } catch (RuntimeException e) {
      log.warn("블로그 {} 마스코트를 저장하지 못함: {}", blogId, e.toString());
    }
  }

  /**
   * 단어 사전으로 바탕을 고르고 색·모자·장식과 장면의 시간·날씨·색은 이름과 태그로 정한다. 장면과 소품 주제는 태그 단어 사전(KeywordScenePicker)으로 먼저
   * 고르고, 맞는 단어가 없으면 바탕 그림과 어울리는 장면 몇 개 중 하나를 이름·태그로 고른다 (D-129).
   */
  static ClaudeMascotClient.Art fallback(String name, List<String> tags) {
    int seed = (name == null ? "" : name).hashCode() * 31 + tags.hashCode();
    Mascot base = KeywordMascotPicker.pick(name, tags);
    CoverScene.Time[] times = CoverScene.Time.values();
    CoverScene.Weather[] weathers = CoverScene.Weather.values();
    CoverScene.Palette[] palettes = CoverScene.Palette.values();
    int h = Math.floorMod(seed / 7, 1 << 20);
    return new ClaudeMascotClient.Art(
        MascotDesign.ofVariant(base, seed),
        new CoverScene(
            KeywordScenePicker.scene(name, tags).orElseGet(() -> sceneFor(base, h / 512)),
            times[h % times.length],
            // 날씨는 절반쯤 맑게
            h / 4 % 2 == 0 ? CoverScene.Weather.CLEAR : weathers[h / 8 % weathers.length],
            palettes[h / 64 % palettes.length],
            0,
            KeywordScenePicker.prop(name, tags).orElse(null)));
  }

  /** 바탕 그림과 어울리는 장면들. 첫째가 원래(D-119) 장면이고, 나머지는 D-129에서 더한 장면이다. */
  static List<CoverScene.Scene> scenesFor(Mascot base) {
    return switch (base) {
      case COMPUTER -> List.of(Scene.DESK, Scene.WORKSHOP, Scene.OFFICE);
      case CAMERA -> List.of(Scene.PHOTO, Scene.MOUNTAIN, Scene.RIVERSIDE, Scene.HANOK);
      case BOOK -> List.of(Scene.STUDY, Scene.LIBRARY, Scene.CLASSROOM);
      case OWL -> List.of(Scene.STUDY, Scene.LIBRARY, Scene.OBSERVATORY, Scene.CLASSROOM);
      case PLANE -> List.of(Scene.SEA, Scene.SKY, Scene.TRAIN, Scene.SPACE_STATION);
      case HEADPHONES -> List.of(Scene.STAGE, Scene.BEDROOM, Scene.RECORD_SHOP, Scene.SUBWAY);
      case FROG -> List.of(Scene.STAGE, Scene.GREENHOUSE, Scene.RIVERSIDE, Scene.FARM);
      case GAMEPAD -> List.of(Scene.GAME, Scene.BEDROOM, Scene.CONVENIENCE);
      case COFFEE -> List.of(Scene.CAFE, Scene.STREET_MARKET, Scene.OFFICE);
      case CAKE -> List.of(Scene.BAKERY, Scene.KITCHEN);
      case BEAR -> List.of(Scene.KITCHEN, Scene.CAMPSITE, Scene.STREET_MARKET, Scene.POJANGMACHA);
      case PENGUIN -> List.of(Scene.SNOWFIELD, Scene.AQUARIUM, Scene.MOUNTAIN, Scene.SPACE_STATION);
      case BIRD -> List.of(Scene.SKY, Scene.MOUNTAIN, Scene.CAMPSITE, Scene.TRAIN, Scene.BUS_STOP);
      case DOG -> List.of(Scene.PARK, Scene.RIVERSIDE, Scene.ROOFTOP, Scene.GYM);
      case FOX -> List.of(Scene.PARK, Scene.ATELIER, Scene.CAMPSITE, Scene.HANOK);
      case CAT ->
          List.of(Scene.GARDEN, Scene.BEDROOM, Scene.FLOWER_SHOP, Scene.ROOFTOP, Scene.LAUNDROMAT);
      case RABBIT -> List.of(Scene.GARDEN, Scene.FLOWER_SHOP, Scene.GREENHOUSE, Scene.FARM);
      case HAMSTER ->
          List.of(Scene.NIGHT_CITY, Scene.STREET_MARKET, Scene.WORKSHOP, Scene.CONVENIENCE);
    };
  }

  /** 바탕 그림에 어울리는 장면 중 하나. 같은 바탕이라도 이름·태그에 따라 다른 장면이 나온다. */
  static CoverScene.Scene sceneFor(Mascot base, int h) {
    List<CoverScene.Scene> scenes = scenesFor(base);
    return scenes.get(Math.floorMod(h, scenes.size()));
  }

  /** 배치 번호를 새로 뽑는다. 지금 장면과 같은 번호는 피한다. */
  static CoverScene withNewSeed(CoverScene chosen, CoverScene current) {
    int seed;
    do {
      seed = ThreadLocalRandom.current().nextInt(CoverScene.SEEDS);
    } while (current != null && current.seed() == seed);
    return chosen.withSeed(seed);
  }

  // 태그에 없을 때 무작위로 고르는 계절. 크리스마스·핼러윈·생일은 1년 내내 남으므로 태그에 있을 때만 붙인다
  private static final List<CoverScene.Event> SEASONS =
      List.of(Event.BLOOM, Event.SUN, Event.FALL, Event.FROST, Event.WET, Event.FLIES);
  private static final List<CoverScene.Backdrop> BACKDROPS =
      Arrays.stream(Backdrop.values()).filter(b -> b != Backdrop.NONE).toList();

  /**
   * 섞기 모드로 바꾸고 배경과 계절·행사를 정한다 (D-129). Claude가 고른 값이 있으면 그대로, 없으면 태그 단어 사전, 그래도 없으면 배경은 10번 중 7번,
   * 계절은 20번 중 9번쯤 무작위로 고른다. 나머지는 장면이 정한 풍경과 장식 없음이다. 벽·바닥 재질, 가구 색, 마스코트 자세, 좌우 뒤집기는 화면이 배치 번호로
   * 정한다.
   */
  static CoverScene withLayers(
      CoverScene chosen, String name, List<String> tags, RandomGenerator random) {
    Backdrop backdrop =
        chosen.backdrop() != null
            ? chosen.backdrop()
            : KeywordScenePicker.backdrop(name, tags)
                .orElseGet(
                    () ->
                        random.nextInt(10) < 7
                            ? BACKDROPS.get(random.nextInt(BACKDROPS.size()))
                            : null);
    Event event =
        chosen.event() != null
            ? chosen.event()
            : KeywordScenePicker.event(name, tags)
                .orElseGet(
                    () ->
                        random.nextInt(20) < 9
                            ? SEASONS.get(random.nextInt(SEASONS.size()))
                            : null);
    return chosen.withLayers(backdrop, event);
  }

  /** 쓰고 있는 조합이면 같은 바탕 안에서 비어 있는 조합으로 바꾼다. 모두 차 있으면 원래 조합을 그대로 쓴다. */
  static MascotDesign unique(MascotDesign wanted, Set<String> taken) {
    for (int i = 0; i < MascotDesign.VARIANTS; i++) {
      MascotDesign d = MascotDesign.ofVariant(wanted.base(), wanted.variant() + i * STEP);
      if (!taken.contains(d.value())) {
        return d;
      }
    }
    return wanted;
  }

  private List<String> tagNames(Long blogId) {
    return blogTagRepository.findWithTagByBlogIdIn(List.of(blogId)).stream()
        .map(BlogTag::getTag)
        .map(t -> t.getName())
        .toList();
  }

  @PreDestroy
  void shutdown() {
    worker.shutdownNow();
  }

  private record Info(String name, List<String> tags, MascotDesign current, CoverScene cover) {}
}
