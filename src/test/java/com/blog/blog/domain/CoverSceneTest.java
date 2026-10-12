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

  @Test
  void layersAreAnOptionalSeventhPart() {
    CoverScene base =
        new CoverScene(
            CoverScene.Scene.BEDROOM,
            CoverScene.Time.NIGHT,
            CoverScene.Weather.CLEAR,
            CoverScene.Palette.DUSK,
            4821);
    CoverScene c = base.withLayers(CoverScene.Backdrop.SEA, CoverScene.Event.XMAS);
    assertThat(c.value()).isEqualTo("bedroom.night.clear.dusk.4821.-.sea~xmas");
    assertThat(CoverScene.parse(c.value())).contains(c);
    assertThat(c.withSeed(7).value()).isEqualTo("bedroom.night.clear.dusk.7.-.sea~xmas");
    // 한쪽만 있거나 둘 다 없어도 섞기 모드다
    assertThat(base.withLayers(null, CoverScene.Event.BLOOM).value())
        .isEqualTo("bedroom.night.clear.dusk.4821.-.~bloom");
    assertThat(base.withLayers(CoverScene.Backdrop.DESERT, null).value())
        .isEqualTo("bedroom.night.clear.dusk.4821.-.desert~");
    CoverScene empty = CoverScene.parse("bedroom.night.clear.dusk.4821.-.~").orElseThrow();
    assertThat(empty.mixed()).isTrue();
    assertThat(empty.backdrop()).isNull();
    assertThat(empty.prop()).isNull();
    assertThat(empty.value()).isEqualTo("bedroom.night.clear.dusk.4821.-.~");
    // 소품 주제와 같이 쓴다
    CoverScene full =
        CoverScene.parse("space-station.sunset.cloudy.forest.99999.travel.harbor~flies")
            .orElseThrow();
    assertThat(full.prop()).isEqualTo(CoverScene.Prop.TRAVEL);
    assertThat(full.backdrop()).isEqualTo(CoverScene.Backdrop.HARBOR);
    assertThat(full.event()).isEqualTo(CoverScene.Event.FLIES);
    // 모르는 배경·계절은 없는 것으로, NONE은 저장하지 않는다
    assertThat(CoverScene.parse("study.day.clear.warm.1.-.moon~party").orElseThrow().value())
        .isEqualTo("study.day.clear.warm.1.-.~");
    assertThat(base.withLayers(CoverScene.Backdrop.NONE, CoverScene.Event.NONE).value())
        .isEqualTo("bedroom.night.clear.dusk.4821.-.~");
    // 섞기 모드가 아니면 배경·계절을 넣어도 버린다(예전 값은 그대로)
    assertThat(
            new CoverScene(
                    CoverScene.Scene.STUDY,
                    CoverScene.Time.DAY,
                    CoverScene.Weather.CLEAR,
                    CoverScene.Palette.WARM,
                    1,
                    null,
                    CoverScene.Backdrop.SEA,
                    CoverScene.Event.XMAS,
                    false)
                .value())
        .isEqualTo("study.day.clear.warm.1");
  }

  @Test
  void longestMixedValueFitsTheColumn() {
    // blogs.cover_scene VARCHAR(60). 모든 장면·배경·계절에 가장 긴 시간·날씨·색·번호·주제를 붙여도 60자 이하
    int longest = 0;
    for (CoverScene.Scene scene : CoverScene.Scene.values()) {
      for (CoverScene.Backdrop b : CoverScene.Backdrop.values()) {
        for (CoverScene.Event e : CoverScene.Event.values()) {
          for (CoverScene.Prop prop : CoverScene.Prop.values()) {
            CoverScene c =
                new CoverScene(
                        scene,
                        CoverScene.Time.SUNSET,
                        CoverScene.Weather.CLOUDY,
                        CoverScene.Palette.FOREST,
                        99_999,
                        prop)
                    .withLayers(b, e);
            assertThat(CoverScene.parse(c.value())).contains(c);
            longest = Math.max(longest, c.value().length());
          }
        }
      }
    }
    assertThat(longest).isLessThanOrEqualTo(60);
  }
}
