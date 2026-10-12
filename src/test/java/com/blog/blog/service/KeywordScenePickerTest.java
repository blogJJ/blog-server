package com.blog.blog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.blog.domain.CoverScene;
import com.blog.blog.domain.CoverScene.Backdrop;
import com.blog.blog.domain.CoverScene.Event;
import com.blog.blog.domain.CoverScene.Prop;
import com.blog.blog.domain.CoverScene.Scene;
import com.blog.blog.domain.Mascot;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 태그로 대표 픽셀 장면과 소품 주제 고르기 (BLG-15, D-129). */
class KeywordScenePickerTest {

  @Test
  void tagsPickNewScenes() {
    assertThat(KeywordScenePicker.scene("주말", List.of("캠핑", "요리"))).contains(Scene.CAMPSITE);
    assertThat(KeywordScenePicker.scene("산 사람", List.of("등산"))).contains(Scene.MOUNTAIN);
    assertThat(KeywordScenePicker.scene("기록", List.of("독서모임"))).contains(Scene.LIBRARY);
    assertThat(KeywordScenePicker.scene("기록", List.of("독서"))).contains(Scene.STUDY);
    assertThat(KeywordScenePicker.scene("우리집", List.of("꽃다발"))).contains(Scene.FLOWER_SHOP);
    assertThat(KeywordScenePicker.scene("우리집", List.of("반려식물"))).contains(Scene.GREENHOUSE);
    assertThat(KeywordScenePicker.scene("밤", List.of("별자리"))).contains(Scene.OBSERVATORY);
    assertThat(KeywordScenePicker.scene("손으로", List.of("목공", "DIY"))).contains(Scene.WORKSHOP);
    assertThat(KeywordScenePicker.scene("Rail log", List.of())).contains(Scene.TRAIN);
  }

  @Test
  void tagsPickLaterScenes() {
    assertThat(KeywordScenePicker.scene("몸만들기", List.of("헬스"))).contains(Scene.GYM);
    assertThat(KeywordScenePicker.scene("오늘 뭐 먹지", List.of("편의점", "신상")))
        .contains(Scene.CONVENIENCE);
    assertThat(KeywordScenePicker.scene("주말", List.of("한옥", "여행"))).contains(Scene.HANOK);
    assertThat(KeywordScenePicker.scene("밤", List.of("포차"))).contains(Scene.POJANGMACHA);
    assertThat(KeywordScenePicker.scene("하루", List.of("출퇴근"))).contains(Scene.SUBWAY);
    assertThat(KeywordScenePicker.scene("하루", List.of("직장인"))).contains(Scene.OFFICE);
    assertThat(KeywordScenePicker.scene("판", List.of("LP"))).contains(Scene.RECORD_SHOP);
    assertThat(KeywordScenePicker.scene("밭", List.of("귀농"))).contains(Scene.FARM);
    // 버스킹은 음악이다
    assertThat(KeywordScenePicker.scene("거리", List.of("버스킹"))).contains(Scene.STAGE);
  }

  @Test
  void tagsSteerBackdropAndEvent() {
    assertThat(KeywordScenePicker.backdrop("기록", List.of("여행"))).contains(Backdrop.HARBOR);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("벚꽃"))).contains(Backdrop.SAKURA);
    assertThat(KeywordScenePicker.event("기록", List.of("벚꽃"))).contains(Event.BLOOM);
    assertThat(KeywordScenePicker.event("우리집", List.of("크리스마스"))).contains(Event.XMAS);
    assertThat(KeywordScenePicker.event("우리집", List.of("할로윈"))).contains(Event.BOO);
    assertThat(KeywordScenePicker.event("우리집", List.of("생일"))).contains(Event.BDAY);
    assertThat(KeywordScenePicker.backdrop("바다", List.of("다이빙"))).contains(Backdrop.UNDER);
    assertThat(KeywordScenePicker.backdrop("길", List.of("사막"))).contains(Backdrop.DESERT);
    assertThat(KeywordScenePicker.backdrop("길", List.of("단풍"))).contains(Backdrop.AUTUMN);
    assertThat(KeywordScenePicker.event("길", List.of("단풍"))).contains(Event.FALL);
    assertThat(KeywordScenePicker.backdrop("길", List.of("시골"))).contains(Backdrop.RICE);
    assertThat(KeywordScenePicker.backdrop("길", List.of("오로라"))).contains(Backdrop.AURORA);
    assertThat(KeywordScenePicker.backdrop("길", List.of("우주"))).contains(Backdrop.SPACE);
    assertThat(KeywordScenePicker.backdrop("이름", List.of("아무거나"))).isEmpty();
    assertThat(KeywordScenePicker.event("이름", List.of("아무거나"))).isEmpty();
  }

  @Test
  void layersComeFromClaudeThenTagsThenRandom() {
    CoverScene base =
        new CoverScene(
            Scene.BEDROOM,
            CoverScene.Time.NIGHT,
            CoverScene.Weather.CLEAR,
            CoverScene.Palette.MINT,
            1);
    Random random = new Random(42);
    // 태그
    CoverScene tagged = MascotService.withLayers(base, "우리집", List.of("크리스마스", "여행"), random);
    assertThat(tagged.value()).isEqualTo("bedroom.night.clear.mint.1.-.harbor~xmas");
    // Claude가 고른 값이 먼저
    CoverScene chosen = base.withLayers(Backdrop.DESERT, Event.BOO);
    assertThat(MascotService.withLayers(chosen, "우리집", List.of("크리스마스"), random).value())
        .isEqualTo("bedroom.night.clear.mint.1.-.desert~boo");
    // 태그에 없으면 무작위. 배경은 늘 붙고(T158), 계절은 20번 중 9번쯤이고 행사 장식은 나오지 않는다
    Set<Backdrop> backdrops = EnumSet.noneOf(Backdrop.class);
    int withBackdrop = 0;
    int withEvent = 0;
    for (int i = 0; i < 2000; i++) {
      CoverScene c = MascotService.withLayers(base, "이름", List.of(), random);
      assertThat(c.mixed()).isTrue();
      if (c.backdrop() != null) {
        withBackdrop++;
        backdrops.add(c.backdrop());
      }
      if (c.event() != null) {
        withEvent++;
        assertThat(c.event()).isNotIn(Event.XMAS, Event.BOO, Event.BDAY);
      }
    }
    assertThat(withBackdrop).isEqualTo(2000);
    assertThat(withEvent).isBetween(750, 1050);
    assertThat(backdrops).hasSize(Backdrop.values().length - 1);
  }

  @Test
  void tagsPickNewBackdrops() {
    assertThat(KeywordScenePicker.backdrop("기록", List.of("화산"))).contains(Backdrop.LAVA);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("폭포"))).contains(Backdrop.FALLS);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("산토리니"))).contains(Backdrop.SANTO);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("풍차"))).contains(Backdrop.MILLS);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("라벤더"))).contains(Backdrop.LILAC);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("야자수"))).contains(Backdrop.PALMS);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("등대"))).contains(Backdrop.STORM);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("한옥마을"))).contains(Backdrop.ROOFS);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("한강 야경"))).contains(Backdrop.BRIDGE);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("밀밭"))).contains(Backdrop.WHEAT);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("빙하"))).contains(Backdrop.BERG);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("대나무"))).contains(Backdrop.BAMBOO);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("고성"))).contains(Backdrop.CASTLE);
    // '서울 야경'은 예전처럼 야경 빌딩, '감성'의 '성'은 성이 아니다
    assertThat(KeywordScenePicker.backdrop("기록", List.of("서울 야경"))).contains(Backdrop.TOWERS);
    assertThat(KeywordScenePicker.backdrop("기록", List.of("감성"))).isEmpty();
  }

  @Test
  void noMatchingWordGivesEmpty() {
    assertThat(KeywordScenePicker.scene("이름", List.of("아무거나"))).isEmpty();
    // '특별'의 '별'처럼 다른 말 속 글자로는 고르지 않는다
    assertThat(KeywordScenePicker.scene("특별한 하루", List.of())).isEmpty();
    // 영어는 낱말 단위로 맞춘다: education 속 cat은 고양이가 아니다
    assertThat(KeywordScenePicker.prop("education", List.of())).isEmpty();
  }

  @Test
  void propFollowsTagsIndependentlyOfScene() {
    assertThat(KeywordScenePicker.prop("책방", List.of("고양이", "독서"))).contains(Prop.CAT);
    assertThat(KeywordScenePicker.prop("책방", List.of("독서", "고양이"))).contains(Prop.BOOK);
    assertThat(KeywordScenePicker.prop("밴드", List.of())).contains(Prop.MUSIC);
  }

  @Test
  void everySceneHasWords() {
    for (Scene scene : Scene.values()) {
      List<String> words = KeywordScenePicker.wordsFor(scene);
      assertThat(words).as(scene.name()).isNotEmpty();
    }
  }

  @Test
  void fallbackReachesEveryScene() {
    Set<Scene> reached = EnumSet.noneOf(Scene.class);
    for (Mascot base : Mascot.values()) {
      for (int h = 0; h < 12; h++) {
        reached.add(MascotService.sceneFor(base, h));
      }
      // 첫째는 예전(D-119) 장면
      assertThat(MascotService.scenesFor(base)).isNotEmpty();
    }
    for (Scene scene : Scene.values()) {
      reached.add(
          KeywordScenePicker.scene("", List.of(KeywordScenePicker.wordsFor(scene).get(0)))
              .orElseThrow());
    }
    assertThat(reached).containsExactlyInAnyOrder(Scene.values());
  }

  @Test
  void bareBaseSpreadsOverRelatedScenes() {
    assertThat(MascotService.scenesFor(Mascot.CAT).get(0)).isEqualTo(Scene.GARDEN);
    Set<Scene> cat = EnumSet.noneOf(Scene.class);
    for (int h = 0; h < 8; h++) {
      cat.add(MascotService.sceneFor(Mascot.CAT, h));
    }
    assertThat(cat).hasSizeGreaterThan(1).contains(Scene.GARDEN, Scene.BEDROOM);
  }

  @Test
  void fallbackUsesTagsForSceneAndProp() {
    ClaudeMascotClient.Art art = MascotService.fallback("주말 기록", List.of("캠핑", "고양이"));
    assertThat(art.cover().scene()).isEqualTo(Scene.CAMPSITE);
    assertThat(art.cover().prop()).isEqualTo(Prop.TRAVEL);
    ClaudeMascotClient.Art plain = MascotService.fallback("아무 이름", List.of());
    assertThat(MascotService.scenesFor(plain.mascot().base())).contains(plain.cover().scene());
    assertThat(plain.cover().prop()).isNull();
  }
}
