package com.blog.blog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
  void readsBasesAddedInD143() {
    // D-143에서 더한 동물 10종과 물건 2종
    assertThat(MascotDesign.parse("hedgehog.mint.beret.heart"))
        .contains(
            new MascotDesign(
                Mascot.HEDGEHOG,
                MascotDesign.Tone.MINT,
                MascotDesign.Hat.BERET,
                MascotDesign.Charm.HEART));
    assertThat(MascotDesign.parse("plant")).contains(MascotDesign.plain(Mascot.PLANT));
    for (String key :
        List.of(
            "panda",
            "koala",
            "pig",
            "sheep",
            "duck",
            "turtle",
            "hedgehog",
            "whale",
            "lion",
            "mouse",
            "pencil",
            "plant")) {
      Mascot m = Mascot.fromKey(key).orElseThrow();
      assertThat(m.key()).isEqualTo(key);
      assertThat(m.label()).isNotBlank();
      // 가장 긴 조합도 DB 칸(VARCHAR(40))에 들어간다
      assertThat(
              new MascotDesign(
                      m,
                      MascotDesign.Tone.ORIGINAL,
                      MascotDesign.Hat.SPROUT,
                      MascotDesign.Charm.SPARKLE)
                  .value())
          .hasSizeLessThanOrEqualTo(40);
    }
    assertThat(Mascot.values()).hasSize(30);
    assertThat(Arrays.stream(Mascot.values()).filter(Mascot::animal)).hasSize(20);
    // 화면(mascot.js)처럼 동물이 앞에 모여 있다
    assertThat(Arrays.stream(Mascot.values()).limit(20)).allMatch(Mascot::animal);
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
