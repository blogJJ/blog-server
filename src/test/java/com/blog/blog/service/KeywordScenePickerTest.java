package com.blog.blog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.blog.domain.CoverScene.Prop;
import com.blog.blog.domain.CoverScene.Scene;
import com.blog.blog.domain.Mascot;
import java.util.EnumSet;
import java.util.List;
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
