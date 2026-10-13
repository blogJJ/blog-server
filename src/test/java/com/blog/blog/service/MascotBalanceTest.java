package com.blog.blog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.blog.domain.Mascot;
import com.blog.blog.domain.MascotDesign;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 마스코트 바탕을 고르게 나누기: 덜 쓰는 바탕 고르기와 몰린 바탕 옮기기 (T169, D-143). */
class MascotBalanceTest {

  @Test
  void picksLeastUsedCandidateAndKeepsOrderOnTies() {
    Map<Mascot, Long> counts = counts(Mascot.COMPUTER, 5, Mascot.OWL, 2, Mascot.PENCIL, 2);

    assertThat(MascotService.leastUsed(List.of(Mascot.COMPUTER, Mascot.OWL, Mascot.PENCIL), counts))
        .isEqualTo(Mascot.OWL);
    // 아무도 안 쓰는 바탕은 0으로 센다
    assertThat(MascotService.leastUsed(List.of(Mascot.COMPUTER, Mascot.PANDA), counts))
        .isEqualTo(Mascot.PANDA);
    assertThat(MascotService.leastUsed(List.of(Mascot.COMPUTER), counts))
        .isEqualTo(Mascot.COMPUTER);
    assertThat(MascotService.leastUsed(List.of(), counts)).isNull();
  }

  @Test
  void ceilingIsOpenBlogsPerBaseRoundedUp() {
    int bases = Mascot.values().length;
    assertThat(MascotService.ceiling(0)).isZero();
    assertThat(MascotService.ceiling(1)).isEqualTo(1);
    assertThat(MascotService.ceiling(bases)).isEqualTo(1);
    assertThat(MascotService.ceiling(bases + 1)).isEqualTo(2);
  }

  @Test
  void rebalanceMovesToKeywordMatchFirstThenAnimal() {
    Map<Mascot, Long> counts = counts(Mascot.COMPUTER, 6, Mascot.OWL, 1, Mascot.BOOK, 2);
    long ceiling = 2;

    // 단어 사전에 맞는 바탕 중 몫보다 적게 쓰는 것. BOOK은 몫에 닿아 있어 뺀다
    assertThat(
            MascotService.rebalanceTarget(
                Mascot.COMPUTER,
                List.of(Mascot.COMPUTER, Mascot.BOOK, Mascot.OWL),
                counts,
                ceiling))
        .isEqualTo(Mascot.OWL);
    // 맞는 바탕이 모두 찼으면 동물 중 가장 적게 쓰는 것(같으면 앞 동물)
    assertThat(
            MascotService.rebalanceTarget(Mascot.COMPUTER, List.of(Mascot.BOOK), counts, ceiling))
        .isEqualTo(Mascot.BEAR);
  }

  @Test
  void rebalanceNeverPicksObjectsWithoutKeywordOrFullBases() {
    Map<Mascot, Long> counts = new EnumMap<>(Mascot.class);
    for (Mascot m : Mascot.values()) {
      counts.put(m, m.animal() ? 2L : 0L);
    }
    counts.put(Mascot.BEAR, 5L);
    // 동물이 모두 몫에 닿았고 맞는 단어도 없으면 옮기지 않는다(물건 바탕은 주제가 맞을 때만)
    assertThat(MascotService.rebalanceTarget(Mascot.BEAR, List.of(), counts, 2)).isNull();
    assertThat(MascotService.rebalanceTarget(Mascot.BEAR, List.of(Mascot.CAKE), counts, 2))
        .isEqualTo(Mascot.CAKE);
  }

  @Test
  void repeatedRebalanceConverges() {
    // 몫을 넘는 바탕에서 몫보다 적은 바탕으로만 옮기므로 몇 번 돌면 더 옮기지 않는다
    Map<Mascot, Long> counts = new EnumMap<>(Mascot.class);
    for (Mascot m : Mascot.values()) {
      counts.put(m, 0L);
    }
    counts.put(Mascot.COMPUTER, 25L);
    counts.put(Mascot.BEAR, 10L);
    counts.put(Mascot.CAT, 5L);
    long open = 40;
    long ceiling = MascotService.ceiling(open);
    int moves = 0;
    boolean changed = true;
    while (changed) {
      changed = false;
      for (Mascot base : Mascot.values()) {
        while (counts.get(base) > ceiling) {
          Mascot to = MascotService.rebalanceTarget(base, List.of(Mascot.BOOK), counts, ceiling);
          if (to == null) {
            break;
          }
          counts.merge(base, -1L, Long::sum);
          counts.merge(to, 1L, Long::sum);
          moves++;
          changed = true;
        }
      }
      assertThat(moves).isLessThan(1000);
    }
    assertThat(counts.values()).allMatch(n -> n <= ceiling);
    assertThat(counts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(open);
  }

  @Test
  void everyBaseHasScenes() {
    for (Mascot base : Mascot.values()) {
      assertThat(MascotService.scenesFor(base)).isNotEmpty();
    }
  }

  @Test
  void fallbackUsesGivenBase() {
    ClaudeMascotClient.Art art = MascotService.fallback("이름", List.of(), Mascot.HEDGEHOG);
    assertThat(art.mascot().base()).isEqualTo(Mascot.HEDGEHOG);
    assertThat(MascotService.scenesFor(Mascot.HEDGEHOG)).contains(art.cover().scene());
  }

  @Test
  void artKeepsAtMostTwoAlternativesOtherThanBase() {
    ClaudeMascotClient.Art art =
        new ClaudeMascotClient.Art(
            MascotDesign.plain(Mascot.OWL),
            null,
            Arrays.asList(Mascot.OWL, null, Mascot.PENCIL, Mascot.PENCIL, Mascot.BOOK, Mascot.CAT));
    assertThat(art.alternatives()).containsExactly(Mascot.PENCIL, Mascot.BOOK);
    assertThat(art.bases()).containsExactly(Mascot.OWL, Mascot.PENCIL, Mascot.BOOK);
  }

  private static Map<Mascot, Long> counts(Object... pairs) {
    Map<Mascot, Long> out = new EnumMap<>(Mascot.class);
    for (int i = 0; i < pairs.length; i += 2) {
      out.put((Mascot) pairs[i], ((Number) pairs[i + 1]).longValue());
    }
    return out;
  }
}
