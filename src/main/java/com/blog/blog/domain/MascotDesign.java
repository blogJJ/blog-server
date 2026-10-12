package com.blog.blog.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * 블로그 마스코트 한 장: 바탕 그림, 색, 모자, 옆에 떠 있는 작은 장식의 조합 (BLG-14, D-116, D-118). DB에는 {@code
 * bear.mint.beret.heart}처럼 점으로 이은 소문자로 저장한다. 바탕 이름만 있는 값(bear)은 기본 색에 장식 없음으로 읽는다.
 */
public record MascotDesign(Mascot base, Tone tone, Hat hat, Charm charm) {

  /** 그림 색. original은 그린 그대로, 나머지는 색만 바꾼다. */
  public enum Tone {
    ORIGINAL,
    PASTEL,
    MINT,
    BERRY,
    SKY,
    COCOA
  }

  public enum Hat {
    NONE,
    BERET,
    PARTY,
    CROWN,
    FLOWER,
    BOW,
    SPROUT,
    BEANIE
  }

  /** 그림 오른쪽 위에 떠 있는 작은 장식. */
  public enum Charm {
    NONE,
    HEART,
    STAR,
    NOTE,
    SPARKLE,
    LEAF
  }

  /** 바탕 그림 하나에 만들 수 있는 조합 수. */
  public static final int VARIANTS =
      Tone.values().length * Hat.values().length * Charm.values().length;

  public MascotDesign {
    tone = tone == null ? Tone.ORIGINAL : tone;
    hat = hat == null ? Hat.NONE : hat;
    charm = charm == null ? Charm.NONE : charm;
  }

  public static MascotDesign plain(Mascot base) {
    return new MascotDesign(base, Tone.ORIGINAL, Hat.NONE, Charm.NONE);
  }

  /** 같은 바탕 그림 안에서 몇 번째 조합인지 (0 ~ VARIANTS-1). */
  public int variant() {
    return (tone.ordinal() * Hat.values().length + hat.ordinal()) * Charm.values().length
        + charm.ordinal();
  }

  public static MascotDesign ofVariant(Mascot base, int variant) {
    int v = Math.floorMod(variant, VARIANTS);
    int charms = Charm.values().length;
    int hats = Hat.values().length;
    return new MascotDesign(
        base,
        Tone.values()[v / charms / hats],
        Hat.values()[v / charms % hats],
        Charm.values()[v % charms]);
  }

  public String value() {
    return String.join(".", base.key(), lower(tone), lower(hat), lower(charm))
        .toLowerCase(Locale.ROOT);
  }

  /** 저장된 값을 읽는다. 바탕 그림을 모르면 빈 값, 나머지 부분을 모르면 기본값. */
  public static Optional<MascotDesign> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    String[] parts = value.trim().split("\\.");
    return Mascot.fromKey(parts[0])
        .map(
            base ->
                new MascotDesign(
                    base,
                    part(Tone.class, parts, 1),
                    part(Hat.class, parts, 2),
                    part(Charm.class, parts, 3)));
  }

  private static <E extends Enum<E>> E part(Class<E> type, String[] parts, int i) {
    if (parts.length <= i) {
      return null;
    }
    for (E e : type.getEnumConstants()) {
      if (e.name().equalsIgnoreCase(parts[i])) {
        return e;
      }
    }
    return null;
  }

  private static String lower(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT);
  }
}
