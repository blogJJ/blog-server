package com.blog.blog.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * 대표 이미지가 없는 블로그에 보여 줄 움직이는 픽셀 장면 (BLG-15, D-119). DB에는 {@code study.night.rain.mint.4821}처럼 장면,
 * 시간, 날씨, 색, 배치 번호를 점으로 이어 저장한다. 그림은 화면(pixel-cover.js)이 이 값으로 그리고, 배치 번호가 다르면 같은 장면이라도 소품과 배치가
 * 달라진다. 태그에 맞는 소품 주제가 있으면 여섯째 칸에 붙인다({@code library.day.clear.ocean.4821.cat}, D-129). 주제가 없으면 예전처럼
 * 다섯 칸이다.
 *
 * <p>섞기 모드(D-129)는 일곱째 칸에 배경(창밖·뒤 풍경)과 계절·행사를 {@code 배경~계절}로 적는다({@code
 * bedroom.night.clear.dusk.4821.-.sea~xmas}). 소품 주제가 없으면 여섯째 칸은 {@code -}, 배경이나 계절이 없으면 그쪽은
 * 비운다({@code ~bloom}, {@code desert~}). 일곱째 칸이 있으면 화면이 벽·바닥 재질, 가구 색, 마스코트 자세, 좌우 뒤집기도 배치 번호로 따로 골라
 * 겹친다. 큰 배치(창·가구·마스코트 자리를 띠째 옮기기), 창 모양, 먼 풍경 높이, 모니터·TV 화면도 배치 번호로 고른다(D-129 확장). 일곱째 칸이 없는 예전 값은
 * 그림이 그대로다. 가장 긴 값도 60자(blog.cover_scene)를 넘지 않는다.
 */
public record CoverScene(
    Scene scene,
    Time time,
    Weather weather,
    Palette palette,
    int seed,
    Prop prop,
    Backdrop backdrop,
    Event event,
    boolean mixed) {

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
    NIGHT_CITY,
    // D-129에서 더한 장면
    LIBRARY,
    ROOFTOP,
    CAMPSITE,
    MOUNTAIN,
    TRAIN,
    AQUARIUM,
    ATELIER,
    WORKSHOP,
    FLOWER_SHOP,
    OBSERVATORY,
    BEDROOM,
    STREET_MARKET,
    RIVERSIDE,
    GREENHOUSE,
    LAUNDROMAT,
    CLASSROOM,
    GYM,
    RECORD_SHOP,
    CONVENIENCE,
    OFFICE,
    SUBWAY,
    BUS_STOP,
    HANOK,
    FARM,
    SPACE_STATION,
    POJANGMACHA,
    // 운동 장면(T158 후속). 운동 태그가 모두 눈밭으로 가던 것을 종목별로 나눈다
    SOCCER,
    TRACK,
    POOL,
    COURT,
    BASEBALL
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
    CREAM,
    OCEAN,
    FOREST,
    PASTEL
  }

  /** 장면 안 작은 소품(동물, 화분, 선반 소품, 벽 장식)을 고를 때 먼저 볼 주제. 태그에서 정한다 (D-129). */
  public enum Prop {
    CAT,
    DOG,
    BIRD,
    PLANT,
    FLOWER,
    BOOK,
    COFFEE,
    MUSIC,
    ART,
    SPORT,
    FOOD,
    STAR,
    TRAVEL,
    GAME,
    CODE
  }

  /**
   * 섞기 모드에서 창밖이나 장면 뒤로 보이는 풍경 (D-129). 실내 장면은 창밖, 바깥 장면은 먼 풍경이 바뀐다. 이름은 6자 이하. NONE은 Claude가 '고르지
   * 않음'으로 답할 때만 쓰고 저장하지 않는다.
   */
  public enum Backdrop {
    NONE,
    HILLS,
    TREES,
    CITY,
    STREET,
    SEA,
    MOUNT,
    SNOW,
    DESERT,
    SPACE,
    AUTUMN,
    SAKURA,
    RICE,
    HARBOR,
    UNDER,
    TOWERS,
    AURORA,
    JUNGLE,
    TULIP,
    CANYON,
    LAKE,
    // D-129 확장(T158)에서 더한 배경
    LAVA,
    FALLS,
    SANTO,
    MILLS,
    LILAC,
    PALMS,
    STORM,
    ROOFS,
    BRIDGE,
    WHEAT,
    BERG,
    BAMBOO,
    CASTLE
  }

  /** 섞기 모드에서 겹치는 계절·행사 장식 (D-129). 이름은 5자 이하. NONE은 저장하지 않는다. */
  public enum Event {
    NONE,
    BLOOM,
    SUN,
    FALL,
    FROST,
    XMAS,
    BOO,
    BDAY,
    WET,
    FLIES
  }

  /** 배치 번호 범위. 화면은 이 수로 소품·배치를 정한다. */
  public static final int SEEDS = 100_000;

  public CoverScene {
    time = time == null ? Time.DAY : time;
    weather = weather == null ? Weather.CLEAR : weather;
    palette = palette == null ? Palette.WARM : palette;
    seed = Math.floorMod(seed, SEEDS);
    backdrop = backdrop == Backdrop.NONE ? null : backdrop;
    event = event == Event.NONE ? null : event;
    if (!mixed) {
      backdrop = null;
      event = null;
    }
  }

  /** 소품 주제 없이 만든다. */
  public CoverScene(Scene scene, Time time, Weather weather, Palette palette, int seed) {
    this(scene, time, weather, palette, seed, null);
  }

  /** 섞기 모드 없이 만든다(D-129 앞부분의 여섯 칸 값). */
  public CoverScene(Scene scene, Time time, Weather weather, Palette palette, int seed, Prop prop) {
    this(scene, time, weather, palette, seed, prop, null, null, false);
  }

  public CoverScene withSeed(int seed) {
    return new CoverScene(scene, time, weather, palette, seed, prop, backdrop, event, mixed);
  }

  /** 장면만 바꾸고 시간·날씨·색·배치 번호·주제·배경·계절은 그대로 둔다. */
  public CoverScene withScene(Scene scene) {
    return new CoverScene(scene, time, weather, palette, seed, prop, backdrop, event, mixed);
  }

  /** 섞기 모드로 바꾸고 배경과 계절·행사를 정한다. null이면 장면이 정한 풍경, 장식 없음. */
  public CoverScene withLayers(Backdrop backdrop, Event event) {
    return new CoverScene(scene, time, weather, palette, seed, prop, backdrop, event, true);
  }

  public String value() {
    String v =
        String.join(".", key(scene), key(time), key(weather), key(palette), String.valueOf(seed));
    if (mixed) {
      return v
          + "."
          + (prop == null ? "-" : key(prop))
          + "."
          + (backdrop == null ? "" : key(backdrop))
          + "~"
          + (event == null ? "" : key(event));
    }
    return prop == null ? v : v + "." + key(prop);
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
    boolean mixed = p.length > 6;
    String[] layers = mixed ? p[6].split("~", -1) : new String[0];
    return Optional.of(
        new CoverScene(
            scene,
            part(Time.class, p, 1),
            part(Weather.class, p, 2),
            part(Palette.class, p, 3),
            seed,
            part(Prop.class, p, 5),
            part(Backdrop.class, layers, 0),
            part(Event.class, layers, 1),
            mixed));
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
