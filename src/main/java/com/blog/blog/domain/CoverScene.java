package com.blog.blog.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * 대표 이미지가 없는 블로그에 보여 줄 움직이는 픽셀 장면 (BLG-15, D-119). DB에는 {@code study.night.rain.mint.4821}처럼 장면,
 * 시간, 날씨, 색, 배치 번호를 점으로 이어 저장한다. 그림은 화면(pixel-cover.js)이 이 값으로 그리고, 배치 번호가 다르면 같은 장면이라도 소품과 배치가
 * 달라진다.
 */
public record CoverScene(Scene scene, Time time, Weather weather, Palette palette, int seed) {

  public enum Scene {
    STUDY,
    DESK,
    KITCHEN,
    CAFE,
    SKY,
    STAGE,
    PHOTO,
    GAME,
    PARK,
    BAKERY,
    SEA,
    SNOWFIELD,
    GARDEN,
    NIGHT_CITY
  }

  public enum Time {
    DAWN,
    DAY,
    SUNSET,
    NIGHT
  }

  public enum Weather {
    CLEAR,
    CLOUDY,
    RAIN,
    SNOW,
    PETALS,
    LEAVES
  }

  public enum Palette {
    WARM,
    MINT,
    BERRY,
    DUSK,
    CREAM
  }

  /** 배치 번호 범위. 화면은 이 수로 소품·배치를 정한다. */
  public static final int SEEDS = 100_000;

  public CoverScene {
    time = time == null ? Time.DAY : time;
    weather = weather == null ? Weather.CLEAR : weather;
    palette = palette == null ? Palette.WARM : palette;
    seed = Math.floorMod(seed, SEEDS);
  }

  public String value() {
    return String.join(
        ".", key(scene), key(time), key(weather), key(palette), String.valueOf(seed));
  }

  /** 저장된 값을 읽는다. 장면을 모르면 빈 값, 나머지를 모르면 기본값. */
  public static Optional<CoverScene> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    String[] p = value.trim().split("\\.");
    Scene scene = part(Scene.class, p, 0);
    if (scene == null) {
      return Optional.empty();
    }
    int seed = 0;
    if (p.length > 4) {
      try {
        seed = Integer.parseInt(p[4]);
      } catch (NumberFormatException e) {
        seed = 0;
      }
    }
    return Optional.of(
        new CoverScene(
            scene,
            part(Time.class, p, 1),
            part(Weather.class, p, 2),
            part(Palette.class, p, 3),
            seed));
  }

  /** 화면에서 쓰는 이름. NIGHT_CITY는 night-city. */
  static String key(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static <E extends Enum<E>> E part(Class<E> type, String[] p, int i) {
    if (p.length <= i) {
      return null;
    }
    for (E e : type.getEnumConstants()) {
      if (key(e).equals(p[i].toLowerCase(Locale.ROOT))) {
        return e;
      }
    }
    return null;
  }
}
