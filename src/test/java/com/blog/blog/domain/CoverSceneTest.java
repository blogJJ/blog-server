package com.blog.blog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 픽셀 장면 값 읽고 쓰기 (BLG-15, D-119). */
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
}
