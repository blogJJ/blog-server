package com.blog.blog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 픽셀 장면 값 읽고 쓰기 (BLG-15, D-119, D-129). */
class CoverSceneTest {

  @Test
  void roundTripsValueWithHyphenatedScene() {
    CoverScene c =
        new CoverScene(
            CoverScene.Scene.NIGHT_CITY,
            CoverScene.Time.SUNSET,
            CoverScene.Weather.PETALS,
            CoverScene.Palette.DUSK,
            4821);
    assertThat(c.value()).isEqualTo("night-city.sunset.petals.dusk.4821");
    assertThat(CoverScene.parse("night-city.sunset.petals.dusk.4821")).contains(c);
  }

  @Test
  void unknownPartsFallBack() {
    assertThat(CoverScene.parse("study"))
        .contains(
            new CoverScene(
                CoverScene.Scene.STUDY,
                CoverScene.Time.DAY,
                CoverScene.Weather.CLEAR,
                CoverScene.Palette.WARM,
                0));
    assertThat(CoverScene.parse("study.noon.storm.neon.x").orElseThrow().seed()).isZero();
    assertThat(CoverScene.parse("castle.day.clear.warm.1")).isEmpty();
    assertThat(CoverScene.parse(null)).isEmpty();
  }

  @Test
  void everySceneAndPaletteRoundTrips() {
    for (CoverScene.Scene scene : CoverScene.Scene.values()) {
      for (CoverScene.Palette palette : CoverScene.Palette.values()) {
        CoverScene c =
            new CoverScene(scene, CoverScene.Time.DAWN, CoverScene.Weather.SNOW, palette, 99_999);
        assertThat(CoverScene.parse(c.value())).contains(c);
        assertThat(c.value()).hasSizeLessThanOrEqualTo(60);
      }
    }
  }

  @Test
  void newScenesUseHyphenatedKeys() {
    CoverScene c =
        new CoverScene(
            CoverScene.Scene.FLOWER_SHOP,
            CoverScene.Time.DAY,
            CoverScene.Weather.CLEAR,
            CoverScene.Palette.OCEAN,
            7);
    assertThat(c.value()).isEqualTo("flower-shop.day.clear.ocean.7");
    assertThat(CoverScene.parse("street-market.night.rain.pastel.12").orElseThrow().scene())
        .isEqualTo(CoverScene.Scene.STREET_MARKET);
  }

  @Test
  void propIsAnOptionalSixthPart() {
    CoverScene withProp =
        new CoverScene(
            CoverScene.Scene.LIBRARY,
            CoverScene.Time.NIGHT,
            CoverScene.Weather.RAIN,
            CoverScene.Palette.FOREST,
            4821,
            CoverScene.Prop.CAT);
    assertThat(withProp.value()).isEqualTo("library.night.rain.forest.4821.cat");
    assertThat(CoverScene.parse(withProp.value())).contains(withProp);
    for (CoverScene.Prop prop : CoverScene.Prop.values()) {
      CoverScene c =
          new CoverScene(
              CoverScene.Scene.STREET_MARKET,
              CoverScene.Time.SUNSET,
              CoverScene.Weather.LEAVES,
              CoverScene.Palette.PASTEL,
              99_999,
              prop);
      assertThat(CoverScene.parse(c.value())).contains(c);
      assertThat(c.value()).hasSizeLessThanOrEqualTo(60);
    }
    // 예전 값은 다섯 칸 그대로이고, 모르는 주제는 없는 것으로 읽는다
    assertThat(CoverScene.parse("study.night.rain.mint.4821").orElseThrow().value())
        .isEqualTo("study.night.rain.mint.4821");
    assertThat(CoverScene.parse("study.night.rain.mint.4821.robot").orElseThrow().prop()).isNull();
    assertThat(withProp.withSeed(5).prop()).isEqualTo(CoverScene.Prop.CAT);
  }
}
