package com.blog.blog.domain;

import java.util.Arrays;
import java.util.Optional;

/** 블로그 마스코트 픽셀 그림: 동물 10종과 주제에 맞는 물건 8종 (BLG-14, D-116). DB에는 소문자 이름(key)으로 저장한다. */
public enum Mascot {
  BEAR("곰"),
  CAT("고양이"),
  DOG("강아지"),
  RABBIT("토끼"),
  FOX("여우"),
  PENGUIN("펭귄"),
  BIRD("새"),
  FROG("개구리"),
  HAMSTER("햄스터"),
  OWL("부엉이"),
  COMPUTER("컴퓨터"),
  CAMERA("카메라"),
  BOOK("책"),
  PLANE("비행기"),
  HEADPHONES("헤드폰"),
  GAMEPAD("게임기"),
  COFFEE("커피"),
  CAKE("케이크");

  private final String label;

  Mascot(String label) {
    this.label = label;
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
