package com.blog.blog.service;

import com.blog.blog.domain.Mascot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 태그 단어 사전으로 마스코트를 고른다 (BLG-14, D-116). Claude API 키가 없거나 호출이 실패했을 때 쓴다. 태그를 앞에서부터 보고 사전 단어가 들어 있으면
 * 그 동물, 하나도 없으면 블로그 이름으로 정해지는 기본값.
 */
public final class KeywordMascotPicker {

  private static final Map<Mascot, List<String>> WORDS = new LinkedHashMap<>();

  static {
    // 주제가 분명한 물건을 먼저 보고, 없으면 분위기에 맞는 동물
    WORDS.put(
        Mascot.COMPUTER,
        List.of(
            "개발", "코딩", "프로그래밍", "자바", "스프링", "백엔드", "프론트", "알고리즘", "컴퓨터", "it", "dev", "code",
            "java", "spring"));
    WORDS.put(Mascot.GAMEPAD, List.of("게임", "롤", "닌텐도", "플스", "game"));
    WORDS.put(Mascot.CAMERA, List.of("사진", "카메라", "출사", "photo", "camera"));
    WORDS.put(Mascot.HEADPHONES, List.of("음악", "노래", "플레이리스트", "music", "song"));
    WORDS.put(Mascot.CAKE, List.of("베이킹", "빵", "디저트", "케이크", "baking", "dessert"));
    WORDS.put(Mascot.COFFEE, List.of("카페", "커피", "coffee", "cafe"));
    WORDS.put(Mascot.PLANE, List.of("해외", "비행", "항공", "flight"));
    WORDS.put(Mascot.BOOK, List.of("독서", "책", "소설", "book", "novel"));
    WORDS.put(Mascot.BEAR, List.of("요리", "레시피", "집밥", "음식", "맛집", "먹방", "food", "cook", "recipe"));
    WORDS.put(Mascot.BIRD, List.of("여행", "캠핑", "등산", "산책", "travel", "trip", "camp"));
    WORDS.put(Mascot.OWL, List.of("공부", "스터디", "시험", "자격증", "영어", "수학", "study", "exam"));
    WORDS.put(Mascot.DOG, List.of("반려", "강아지", "펫", "가족", "육아", "아기", "dog", "pet", "family"));
    WORDS.put(Mascot.CAT, List.of("고양이", "냥", "cat"));
    WORDS.put(Mascot.RABBIT, List.of("일기", "일상", "감성", "글쓰기", "꽃", "diary", "daily"));
    WORDS.put(Mascot.FOX, List.of("그림", "디자인", "미술", "art", "design"));
    WORDS.put(
        Mascot.PENGUIN,
        List.of("운동", "헬스", "다이어트", "수영", "축구", "야구", "러닝", "겨울", "sport", "fitness", "run"));
    WORDS.put(Mascot.FROG, List.of("기타", "피아노", "밴드", "취미", "hobby"));
    WORDS.put(
        Mascot.HAMSTER,
        List.of("재테크", "주식", "경제", "돈", "저축", "쇼핑", "리뷰", "money", "stock", "review"));
  }

  private KeywordMascotPicker() {}

  /** 사전에 맞는 단어가 있으면 그 동물. */
  public static Optional<Mascot> byWords(String name, List<String> tags) {
    for (String tag : tags) {
      Optional<Mascot> m = match(tag);
      if (m.isPresent()) {
        return m;
      }
    }
    return match(name);
  }

  /** 사전에 없으면 이름으로 정해지는 기본값까지 써서 항상 하나를 고른다. */
  public static Mascot pick(String name, List<String> tags) {
    return byWords(name, tags).orElseGet(() -> byName(name));
  }

  static Mascot byName(String name) {
    Mascot[] all = Mascot.values();
    return all[Math.floorMod(name == null ? 0 : name.hashCode(), all.length)];
  }

  private static Optional<Mascot> match(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    String t = text.toLowerCase(Locale.ROOT);
    List<String> words = List.of(t.split("[^a-z0-9]+"));
    for (Map.Entry<Mascot, List<String>> e : WORDS.entrySet()) {
      for (String w : e.getValue()) {
        // 영어 단어는 다른 단어 안에 들어 있는 경우가 많아(education의 cat) 낱말 단위로만 맞춘다
        boolean hit = w.chars().allMatch(c -> c < 128) ? words.contains(w) : t.contains(w);
        if (hit) {
          return Optional.of(e.getKey());
        }
      }
    }
    return Optional.empty();
  }
}
