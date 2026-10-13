package com.blog.blog.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 블로그 마스코트 픽셀 그림: 동물 20종과 주제에 맞는 물건 10종 (BLG-14, D-116). 작게 그리면 바탕 모양만 보여서 블로그마다 겹쳐 보이지 않게 D-143에서
 * 동물 10종과 물건 2종을 더했다. DB에는 소문자 이름(key)으로 저장한다. 화면(mascot.js)도 동물을 앞에 둔 같은 순서를 쓴다.
 */
public enum Mascot {
  BEAR("곰", true),
  CAT("고양이", true),
  DOG("강아지", true),
  RABBIT("토끼", true),
  FOX("여우", true),
  PENGUIN("펭귄", true),
  BIRD("새", true),
  FROG("개구리", true),
  HAMSTER("햄스터", true),
  OWL("부엉이", true),
  // D-143에서 더한 동물
  PANDA("판다", true),
  KOALA("코알라", true),
  PIG("돼지", true),
  SHEEP("양", true),
  DUCK("오리", true),
  TURTLE("거북이", true),
  HEDGEHOG("고슴도치", true),
  WHALE("고래", true),
  LION("사자", true),
  MOUSE("생쥐", true),
  COMPUTER("컴퓨터", false),
  CAMERA("카메라", false),
  BOOK("책", false),
  PLANE("비행기", false),
  HEADPHONES("헤드폰", false),
  GAMEPAD("게임기", false),
  COFFEE("커피", false),
  CAKE("케이크", false),
  // D-143에서 더한 물건
  PENCIL("연필", false),
  PLANT("화분", false);

  private final String label;
  private final boolean animal;

  Mascot(String label, boolean animal) {
    this.label = label;
    this.animal = animal;
  }

  /** 동물 그림이면 true. 주제와 상관없이 어느 블로그에나 어울려 바탕을 고르게 나눌 때 쓴다 (D-143). */
  public boolean animal() {
    return animal;
  }

  public String key() {
    return name().toLowerCase();
  }

  public String label() {
    return label;
  }

  public static Optional<Mascot> fromKey(String key) {
    if (key == null) {
      return Optional.empty();
    }
    String k = key.trim();
    return Arrays.stream(values()).filter(m -> m.key().equalsIgnoreCase(k)).findFirst();
  }
}
