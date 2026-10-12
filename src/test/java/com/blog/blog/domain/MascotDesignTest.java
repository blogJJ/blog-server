package com.blog.blog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 마스코트 조합 값 읽고 쓰기 (BLG-14, D-118). */
class MascotDesignTest {

  @Test
  void roundTripsValue() {
    MascotDesign d =
        new MascotDesign(
            Mascot.FOX, MascotDesign.Tone.BERRY, MascotDesign.Hat.CROWN, MascotDesign.Charm.NOTE);
    assertThat(d.value()).isEqualTo("fox.berry.crown.note");
    assertThat(MascotDesign.parse("fox.berry.crown.note")).contains(d);
  }

  @Test
  void baseOnlyAndUnknownParts() {
    assertThat(MascotDesign.parse("cat")).contains(MascotDesign.plain(Mascot.CAT));
    assertThat(MascotDesign.parse("cat.gold.wizard.none")).contains(MascotDesign.plain(Mascot.CAT));
    assertThat(MascotDesign.parse("dragon.mint.none.none")).isEmpty();
    assertThat(MascotDesign.parse(null)).isEmpty();
  }

  @Test
  void variantsCoverEveryCombinationOnce() {
    Set<String> seen = new HashSet<>();
    for (int v = 0; v < MascotDesign.VARIANTS; v++) {
      MascotDesign d = MascotDesign.ofVariant(Mascot.BEAR, v);
      assertThat(d.variant()).isEqualTo(v);
      seen.add(d.value());
    }
    assertThat(seen).hasSize(MascotDesign.VARIANTS);
  }
}
