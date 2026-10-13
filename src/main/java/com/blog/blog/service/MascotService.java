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
import com.blog.board.domain.Tag;
import jakarta.annotation.PreDestroy;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * 고르고, 배치 번호는 저장할 때마다 새로 뽑아 같은 장면이라도 블로그마다·태그를 바꿀 때마다 그림이 달라진다. 작게 그리면 바탕 모양만 보이므로 주제에 맞는 바탕 후보 중
 * 쓰는 블로그가 가장 적은 바탕을 쓰고, 이미 한 바탕에 몰린 블로그는 예약 작업이 조금씩 다른 바탕으로 옮긴다 (T169, D-143).
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
  // 예전 장면을 새로 고르는 수(한 번 돌 때). Claude를 부르지 않아 빠르다
  private static final int REFRESH_BATCH = 100;
  // 겹칠 때 다음 조합으로 건너뛰는 폭. 조합 수(288)와 서로소라 모든 조합을 한 번씩 돈다
  private static final int STEP = 115;
  // 한 바탕에 몰린 마스코트를 옮기는 수(한 번 돌 때). Claude를 부르지 않는다 (D-143)
  private static final int REBALANCE_BATCH = 50;
  // 옮길 블로그를 찾을 때 한 바탕에서 읽는 수
  private static final int REBALANCE_SCAN = 500;

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

  /**
   * 마스코트나 장면이 없는 블로그를 채운다. 기존 블로그와, 고르다 서버가 꺼진 블로그용. 섞기 모드가 아닌 예전 장면도 한 번 새로 고른다(T158, D-129 확장).
   */
  @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT10M")
  @SchedulerLock(name = "mascotBackfill", lockAtMostFor = "PT9M")
  public void backfill() {
    if (!enabled) {
      return;
    }
    List<Long> ids = blogRepository.findIdsWithoutMascot(PageRequest.of(0, BACKFILL_BATCH));
    ids.forEach(this::assignSafely);
    refreshLegacyCovers();
    refreshSportCovers();
    rebalanceMascots();
  }

  /**
   * 한 바탕에 몰린 마스코트를 덜 쓰는 바탕으로 옮긴다 (T169, D-143). 바탕마다 쓰는 블로그 수가 {@link #ceiling 고른 몫}(열린 블로그 수 ÷ 바탕
   * 수, 올림)을 넘으면 넘는 만큼 옮긴다. 옮길 바탕은 태그·이름 단어 사전에 맞는 바탕, 없으면 동물 중 몫보다 적게 쓰는 바탕에서 가장 적게 쓰는 것이다. 몫을 넘는
   * 바탕에서 몫보다 적은 바탕으로만 옮기므로 몇 번 돌면 더 바뀌지 않는다. 마스코트 칸만 바꾸고(색·모자·장식은 그대로 두되 겹치면 다른 조합) 장면과 대표
   * 이미지(cover_image)는 그대로 두며 Claude는 부르지 않는다.
   *
   * @return 옮긴 블로그 수
   */
  public int rebalanceMascots() {
    Map<Mascot, Long> counts = baseCounts();
    long open = counts.values().stream().mapToLong(Long::longValue).sum();
    long ceiling = ceiling(open);
    int moved = 0;
    for (Mascot base : Mascot.values()) {
      if (moved >= REBALANCE_BATCH) {
        break;
      }
      if (counts.get(base) > ceiling) {
        moved += moveFrom(base, counts, ceiling, REBALANCE_BATCH - moved);
      }
    }
    return moved;
  }

  /** 몫을 넘은 바탕에서 블로그를 하나씩 옮긴다. 몫까지 줄었거나 budget만큼 옮기면 멈춘다. @return 옮긴 수 */
  private int moveFrom(Mascot base, Map<Mascot, Long> counts, long ceiling, int budget) {
    int moved = 0;
    for (Long id : blogRepository.findOpenIdsByMascotBase(base.key(), REBALANCE_SCAN)) {
      if (moved >= budget || counts.get(base) <= ceiling) {
        break;
      }
      try {
        Mascot to = rebalanceOne(id, base, counts, ceiling);
        if (to != null) {
          counts.merge(base, -1L, Long::sum);
          counts.merge(to, 1L, Long::sum);
          moved++;
        }
      } catch (RuntimeException e) {
        log.warn("블로그 {} 마스코트 바탕을 옮기지 못함: {}", id, e.toString());
      }
    }
    return moved;
  }

  private Mascot rebalanceOne(Long blogId, Mascot from, Map<Mascot, Long> counts, long ceiling) {
    return tx.execute(
        s ->
            blogRepository
                .findById(blogId)
                .filter(Blog::isOpen)
                .filter(b -> b.getMascot() != null && b.getMascot().base() == from)
                .map(
                    b -> {
                      Mascot to =
                          rebalanceTarget(
                              from,
                              KeywordMascotPicker.matches(b.getName(), tagNames(b.getId())),
                              counts,
                              ceiling);
                      if (to == null) {
                        return null;
                      }
                      MascotDesign now = b.getMascot();
                      b.changeMascot(
                          unique(
                              new MascotDesign(to, now.tone(), now.hat(), now.charm()),
                              new HashSet<>(blogRepository.findMascotsInUse(to.key(), blogId))));
                      return to;
                    })
                .orElse(null));
  }

  /** 바탕마다 쓰는 열린 블로그 수. 아무도 안 쓰는 바탕은 0. */
  private Map<Mascot, Long> baseCounts() {
    Map<Mascot, Long> counts = new EnumMap<>(Mascot.class);
    for (Mascot m : Mascot.values()) {
      counts.put(m, 0L);
    }
    for (Object[] row : blogRepository.countMascotBases()) {
      Mascot.fromKey(String.valueOf(row[0]))
          .ifPresent(m -> counts.merge(m, ((Number) row[1]).longValue(), Long::sum));
    }
    return counts;
  }

  /** 바탕마다 고르게 나눴을 때의 몫: 열린 블로그 수 ÷ 바탕 수를 올린 값 (D-143). */
  static long ceiling(long openBlogs) {
    long bases = Mascot.values().length;
    return (openBlogs + bases - 1) / bases;
  }

  /**
   * 후보 중 쓰는 블로그가 가장 적은 바탕 (D-143). 수가 같으면 앞 후보. 후보가 없으면 null.
   *
   * @param counts 바탕별 쓰는 블로그 수. 없는 바탕은 0
   */
  static Mascot leastUsed(List<Mascot> candidates, Map<Mascot, Long> counts) {
    Mascot best = null;
    long min = Long.MAX_VALUE;
    for (Mascot m : candidates) {
      long n = counts.getOrDefault(m, 0L);
      if (n < min) {
        best = m;
        min = n;
      }
    }
    return best;
  }

  /**
   * 몫을 넘는 바탕({@code from})의 블로그를 옮길 바탕 (D-143). 단어 사전에 맞는 바탕 중 몫보다 적게 쓰는 바탕이 있으면 그중 가장 적게 쓰는 것, 없으면
   * 동물 중에서 같은 방법으로 고른다. 옮길 곳이 없으면 null.
   */
  static Mascot rebalanceTarget(
      Mascot from, List<Mascot> matches, Map<Mascot, Long> counts, long ceiling) {
    Mascot byWords =
        leastUsed(
            matches.stream()
                .filter(m -> m != from && counts.getOrDefault(m, 0L) < ceiling)
                .toList(),
            counts);
    if (byWords != null) {
      return byWords;
    }
    return leastUsed(
        Arrays.stream(Mascot.values())
            .filter(m -> m.animal() && m != from && counts.getOrDefault(m, 0L) < ceiling)
            .toList(),
        counts);
  }

  /**
   * 운동 태그가 있거나 장면이 눈밭인 블로그의 장면을 새 운동 사전으로 한 번 다시 고른다 (T158 후속). 운동 단어가 모두 눈밭으로 가던 때 고른 장면을 바로잡는다.
   * 지금 장면이 이 태그로 고를 수 있는 장면이면(헬스장, 운동 장면, 겨울 단어가 있는 눈밭) 그대로 두므로 두 번째부터는 바꾸지 않는다. 장면 칸만 바꾸고 나머지 칸,
   * 마스코트, 대표 이미지(cover_image)는 그대로 두며 Claude는 부르지 않는다.
   *
   * @return 바꾼 블로그 수
   */
  public int refreshSportCovers() {
    int changed = 0;
    long after = 0;
    String pattern = KeywordScenePicker.sportPattern();
    List<Long> ids;
    while (!(ids = blogRepository.findIdsForSportRefresh(pattern, after, REFRESH_BATCH))
        .isEmpty()) {
      after = ids.get(ids.size() - 1);
      for (Long id : ids) {
        try {
          if (refreshSportCover(id)) {
            changed++;
          }
        } catch (RuntimeException e) {
          log.warn("블로그 {} 운동 장면을 다시 고르지 못함: {}", id, e.toString());
        }
      }
    }
    return changed;
  }

  private boolean refreshSportCover(Long blogId) {
    Boolean done =
        tx.execute(
            s ->
                blogRepository
                    .findById(blogId)
                    .filter(Blog::isOpen)
                    .map(
                        b -> {
                          CoverScene next =
                              sportCover(
                                  b.getName(),
                                  tagNames(b.getId()),
                                  b.getCoverScene(),
                                  ThreadLocalRandom.current());
                          if (next == null) {
                            return false;
                          }
                          b.changeCoverScene(next);
                          return true;
                        })
                    .orElse(false));
    return Boolean.TRUE.equals(done);
  }

  /**
   * 운동 장면 다시 고르기(T158 후속)에서 바꿀 장면. 바꾸지 않으면 null. 겨울 단어가 있는 눈밭과 이 태그로 고를 수 있는 장면은 그대로 둔다. 운동 단어가 없는
   * 눈밭은 사전이 고르는 장면, 사전에도 없으면 운동 장면 중 하나로 바꾼다.
   */
  static CoverScene sportCover(
      String name, List<String> tags, CoverScene current, RandomGenerator random) {
    if (current == null) {
      return null;
    }
    Scene now = current.scene();
    if (now == Scene.SNOWFIELD && KeywordScenePicker.hasWinterWord(tags)) {
      return null;
    }
    Set<Scene> possible = KeywordScenePicker.possibleScenes(name, tags);
    if (KeywordScenePicker.hasSportWord(tags) || now == Scene.SNOWFIELD) {
      if (possible.contains(now)) {
        return null;
      }
      List<Scene> pool = KeywordScenePicker.SPORT_POOL;
      Scene next =
          KeywordScenePicker.scene(name, tags, random.nextInt(1 << 16))
              .orElseGet(() -> pool.get(random.nextInt(pool.size())));
      return current.withScene(next);
    }
    return null;
  }

  /**
   * 섞기 모드가 아닌 예전 픽셀 장면(일곱째 칸 없음)을 가진 블로그에 새 장면을 한 번 고른다 (T158, D-129 확장). 장면은 태그 단어 사전, 맞는 단어가 없으면
   * 지금과 다른 장면을 무작위로 고르고 배경은 늘 붙인다. Claude는 부르지 않고 마스코트와 대표 이미지(cover_image)는 그대로 둔다. 새 값에는 {@code
   * ~}가 붙어 다음에는 고르지 않는다.
   *
   * @return 바꾼 블로그 수
   */
  public int refreshLegacyCovers() {
    int changed = 0;
    for (Long id : blogRepository.findIdsWithLegacyCover(REFRESH_BATCH)) {
      try {
        if (refreshCover(id)) {
          changed++;
        }
      } catch (RuntimeException e) {
        log.warn("블로그 {} 대표 장면을 새로 고르지 못함: {}", id, e.toString());
      }
    }
    return changed;
  }

  private boolean refreshCover(Long blogId) {
    Boolean done =
        tx.execute(
            s ->
                blogRepository
                    .findById(blogId)
                    .filter(Blog::isOpen)
                    .map(
                        b -> {
                          b.changeCoverScene(
                              freshCover(
                                  b.getName(),
                                  tagNames(b.getId()),
                                  b.getCoverScene(),
                                  ThreadLocalRandom.current()));
                          return true;
                        })
                    .orElse(false));
    return Boolean.TRUE.equals(done);
  }

  /**
   * 예전 장면을 대신할 새 섞기 모드 장면. 장면은 태그 단어 사전, 없으면 지금과 다른 장면을 무작위로 고르고 시간·날씨·색·배치 번호도 새로 뽑는다. 소품 주제는 태그,
   * 없으면 지금 주제를 그대로 둔다. 배경은 태그, 없으면 무작위로 늘 붙이고 계절·행사는 {@link #withLayers}와 같다.
   */
  static CoverScene freshCover(
      String name, List<String> tags, CoverScene current, RandomGenerator random) {
    Scene[] scenes = Scene.values();
    Scene scene =
        KeywordScenePicker.scene(name, tags, random.nextInt(1 << 16))
            .orElseGet(
                () -> {
                  Scene s;
                  do {
                    s = scenes[random.nextInt(scenes.length)];
                  } while (current != null && s == current.scene());
                  return s;
                });
    CoverScene.Weather[] weathers = CoverScene.Weather.values();
    int seed;
    do {
      seed = random.nextInt(CoverScene.SEEDS);
    } while (current != null && current.seed() == seed);
    CoverScene base =
        new CoverScene(
            scene,
            CoverScene.Time.values()[random.nextInt(CoverScene.Time.values().length)],
            // 날씨는 절반쯤 맑게
            random.nextBoolean()
                ? CoverScene.Weather.CLEAR
                : weathers[random.nextInt(weathers.length)],
            CoverScene.Palette.values()[random.nextInt(CoverScene.Palette.values().length)],
            seed,
            KeywordScenePicker.prop(name, tags).orElse(current == null ? null : current.prop()));
    return withLayers(base, name, tags, random);
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
    Optional<ClaudeMascotClient.Art> chosen = claude.choose(info.name(), info.tags());
    // 주제에 맞는 바탕 후보 중 다른 블로그가 가장 적게 쓰는 바탕 (D-143). 이 블로그의 지금 바탕은 세지 않는다
    List<Mascot> candidates =
        chosen
            .map(ClaudeMascotClient.Art::bases)
            .orElseGet(() -> KeywordMascotPicker.candidates(info.name(), info.tags()));
    Map<Mascot, Long> counts = tx.execute(s -> baseCounts());
    if (info.current() != null) {
      counts.merge(info.current().base(), -1L, Long::sum);
    }
    Mascot base = leastUsed(candidates, counts);
    ClaudeMascotClient.Art art = chosen.orElseGet(() -> fallback(info.name(), info.tags(), base));
    MascotDesign wanted =
        new MascotDesign(base, art.mascot().tone(), art.mascot().hat(), art.mascot().charm());
    CoverScene cover =
        withLayers(
            withNewSeed(art.cover(), info.cover()),
            info.name(),
            info.tags(),
            ThreadLocalRandom.current());
    // 한 번에 하나씩 만들기 때문에(worker, ShedLock) 읽고 저장하는 사이에 같은 조합이 끼어들지 않는다
    return tx.execute(
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
    return fallback(name, tags, KeywordMascotPicker.pick(name, tags));
  }

  /** {@link #fallback(String, List)}와 같되 바탕은 정해 둔 것(D-143에서 고르게 나눈 바탕)을 쓴다. */
  static ClaudeMascotClient.Art fallback(String name, List<String> tags, Mascot base) {
    int seed = (name == null ? "" : name).hashCode() * 31 + tags.hashCode();
    CoverScene.Time[] times = CoverScene.Time.values();
    CoverScene.Weather[] weathers = CoverScene.Weather.values();
    CoverScene.Palette[] palettes = CoverScene.Palette.values();
    int h = Math.floorMod(seed / 7, 1 << 20);
    return new ClaudeMascotClient.Art(
        MascotDesign.ofVariant(base, seed),
        new CoverScene(
            KeywordScenePicker.scene(name, tags, h).orElseGet(() -> sceneFor(base, h / 512)),
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
      // D-143에서 더한 바탕
      case PANDA -> List.of(Scene.GARDEN, Scene.HANOK, Scene.BEDROOM, Scene.PARK);
      case KOALA -> List.of(Scene.BEDROOM, Scene.PARK, Scene.ROOFTOP, Scene.GREENHOUSE);
      case PIG -> List.of(Scene.KITCHEN, Scene.POJANGMACHA, Scene.STREET_MARKET, Scene.CONVENIENCE);
      case SHEEP -> List.of(Scene.FARM, Scene.MOUNTAIN, Scene.BEDROOM, Scene.ATELIER);
      case DUCK -> List.of(Scene.RIVERSIDE, Scene.PARK, Scene.POOL, Scene.LAUNDROMAT);
      case TURTLE -> List.of(Scene.SEA, Scene.AQUARIUM, Scene.RIVERSIDE, Scene.GARDEN);
      case HEDGEHOG -> List.of(Scene.BEDROOM, Scene.LIBRARY, Scene.GARDEN, Scene.FLOWER_SHOP);
      case WHALE -> List.of(Scene.SEA, Scene.AQUARIUM, Scene.POOL, Scene.SKY);
      case LION -> List.of(Scene.GYM, Scene.SOCCER, Scene.OFFICE, Scene.STAGE);
      case MOUSE -> List.of(Scene.CONVENIENCE, Scene.KITCHEN, Scene.LIBRARY, Scene.SUBWAY);
      case PENCIL -> List.of(Scene.STUDY, Scene.CLASSROOM, Scene.LIBRARY, Scene.ATELIER);
      case PLANT -> List.of(Scene.GREENHOUSE, Scene.GARDEN, Scene.FLOWER_SHOP, Scene.ROOFTOP);
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
   * 섞기 모드로 바꾸고 배경과 계절·행사를 정한다 (D-129). Claude가 고른 값이 있으면 그대로, 없으면 태그 단어 사전, 그래도 없으면 배경은 늘(T158),
   * 계절은 20번 중 9번쯤 무작위로 고른다. 계절이 안 붙으면 장식 없음이다. 벽·바닥 재질, 가구 색, 마스코트 자세, 좌우 뒤집기, 큰 배치, 화면 그림은 화면이 배치
   * 번호로 정한다.
   */
  static CoverScene withLayers(
      CoverScene chosen, String name, List<String> tags, RandomGenerator random) {
    Backdrop backdrop =
        chosen.backdrop() != null
            ? chosen.backdrop()
            : KeywordScenePicker.backdrop(name, tags)
                // 배경이 늘 바뀌게 비워 두지 않는다 (T158)
                .orElseGet(() -> BACKDROPS.get(random.nextInt(BACKDROPS.size())));
    Event event =
        chosen.event() != null
            ? chosen.event()
            : KeywordScenePicker.event(name, tags).orElseGet(() -> randomSeason(random));
    return chosen.withLayers(backdrop, event);
  }

  /** 10번 중 4.5번꼴로 계절 장식을 고른다. 나머지는 장식 없음(null) */
  private static Event randomSeason(RandomGenerator random) {
    return random.nextInt(20) < 9 ? SEASONS.get(random.nextInt(SEASONS.size())) : null;
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
        .map(Tag::getName)
        .toList();
  }

  @PreDestroy
  void shutdown() {
    worker.shutdownNow();
  }

  private record Info(String name, List<String> tags, MascotDesign current, CoverScene cover) {}
}
