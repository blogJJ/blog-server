package com.blog.blog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.blog.blog.domain.Mascot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 태그 단어 사전 (BLG-14, D-116). */
class KeywordMascotPickerTest {

  @Test
  void picksByFirstMatchingTag() {
    assertThat(KeywordMascotPicker.pick("민준의 식탁", List.of("집밥", "자취요리"))).isEqualTo(Mascot.BEAR);
    assertThat(KeywordMascotPicker.pick("떠나요", List.of("해외여행"))).isEqualTo(Mascot.PLANE);
    assertThat(KeywordMascotPicker.pick("주말 나들이", List.of("캠핑"))).isEqualTo(Mascot.BIRD);
    assertThat(KeywordMascotPicker.pick("도윤 개발 블로그", List.of("spring", "java")))
        .isEqualTo(Mascot.COMPUTER);
    assertThat(KeywordMascotPicker.pick("주말 책방", List.of("독서모임"))).isEqualTo(Mascot.BOOK);
    assertThat(KeywordMascotPicker.pick("우리집 냥이", List.of("고양이"))).isEqualTo(Mascot.CAT);
    assertThat(KeywordMascotPicker.pick("공부방", List.of("자격증"))).isEqualTo(Mascot.OWL);
  }

  @Test
  void usesNameWhenTagsDoNotMatch() {
    assertThat(KeywordMascotPicker.pick("헬스 일지", List.of("기록"))).isEqualTo(Mascot.PENGUIN);
  }

  @Test
  void shortEnglishWordMustMatchWholeTag() {
    // "it"이 "kitchen" 안에, "cat"이 "education" 안에 있어도 맞추지 않는다
    assertThat(KeywordMascotPicker.byWords("", List.of("kitchen", "education"))).isEmpty();
    assertThat(KeywordMascotPicker.byWords("", List.of("spring-boot"))).contains(Mascot.COMPUTER);
    assertThat(KeywordMascotPicker.byWords("", List.of("IT"))).contains(Mascot.COMPUTER);
  }

  @Test
  void fallsBackToStableDefaultFromName() {
    Mascot first = KeywordMascotPicker.pick("아무 이름", List.of("ㅁㅁ"));
    assertThat(KeywordMascotPicker.pick("아무 이름", List.of())).isEqualTo(first);
    assertThat(KeywordMascotPicker.pick(null, List.of())).isNotNull();
  }
}
