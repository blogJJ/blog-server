package com.blog.blog.service;

import com.blog.blog.domain.Mascot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 태그 단어 사전으로 마스코트를 고른다 (BLG-14, D-116). Claude API 키가 없거나 호출이 실패했을 때 쓴다. 태그를 앞에서부터 보고 사전 단어가 들어 있으면
 * 그 동물, 하나도 없으면 블로그 이름으로 정해지는 기본값. 바탕을 고르게 나눌 때(D-143)는 태그와 이름에 맞는 바탕을 모두 후보로 돌려준다.
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
    // D-143에서 더한 바탕. 앞의 바탕과 같은 단어도 있어 고르게 나눌 때 후보가 늘어난다
    WORDS.put(Mascot.PENCIL, List.of("글쓰기", "일기", "에세이", "필기", "작문", "다이어리", "writing", "essay"));
    WORDS.put(
        Mascot.PLANT, List.of("식물", "가드닝", "텃밭", "다육", "화분", "정원", "plant", "garden", "gardening"));
    WORDS.put(Mascot.PANDA, List.of("판다", "중국", "대나무", "힐링", "느긋", "panda"));
    WORDS.put(Mascot.KOALA, List.of("코알라", "호주", "낮잠", "수면", "koala", "sleep"));
    WORDS.put(Mascot.PIG, List.of("먹방", "맛집", "야식", "간식", "돼지", "pig"));
    WORDS.put(Mascot.SHEEP, List.of("뜨개", "니트", "털실", "양모", "knit", "sheep"));
    WORDS.put(Mascot.DUCK, List.of("오리", "목욕", "물놀이", "호수", "육아", "duck"));
    WORDS.put(Mascot.TURTLE, List.of("거북", "천천히", "느리게", "꾸준", "미니멀", "turtle", "slow"));
    WORDS.put(Mascot.HEDGEHOG, List.of("고슴도치", "내향", "집순이", "집돌이", "소심", "hedgehog"));
    WORDS.put(Mascot.WHALE, List.of("고래", "바다", "다이빙", "서핑", "수영", "해양", "whale", "ocean", "surf"));
    WORDS.put(Mascot.LION, List.of("사자", "자기계발", "동기부여", "리더", "축구", "lion"));
    WORDS.put(Mascot.MOUSE, List.of("생쥐", "치즈", "자취", "절약", "가계부", "mouse"));
  }

  private KeywordMascotPicker() {}

  /** 사전에 맞는 단어가 있으면 그 동물. 맞는 바탕이 여럿이면 앞 태그, 사전 앞쪽 바탕이 먼저다. */
  public static Optional<Mascot> byWords(String name, List<String> tags) {
    return matches(name, tags).stream().findFirst();
  }

  /** 태그(앞에서부터)와 이름에 맞는 바탕을 모두, 겹치지 않게 돌려준다 (D-143). 맞는 단어가 없으면 빈 목록. */
  public static List<Mascot> matches(String name, List<String> tags) {
    LinkedHashSet<Mascot> out = new LinkedHashSet<>();
    for (String tag : tags) {
      out.addAll(matchAll(tag));
    }
    out.addAll(matchAll(name));
    return List.copyOf(out);
  }

  /**
   * 바탕을 고르게 나눌 때 볼 후보 (D-143). 사전에 맞는 바탕이 있으면 그것들, 없으면 모든 바탕을 이름으로 정해지는 기본값부터 차례로 돌려준다. 쓰는 블로그 수가
   * 같으면 앞 후보를 고른다.
   */
  public static List<Mascot> candidates(String name, List<String> tags) {
    List<Mascot> matched = matches(name, tags);
    if (!matched.isEmpty()) {
      return matched;
    }
    Mascot[] all = Mascot.values();
    int start = byName(name).ordinal();
    List<Mascot> out = new ArrayList<>(all.length);
    for (int i = 0; i < all.length; i++) {
      out.add(all[(start + i) % all.length]);
    }
    return out;
  }

  /** 사전에 없으면 이름으로 정해지는 기본값까지 써서 항상 하나를 고른다. */
  public static Mascot pick(String name, List<String> tags) {
    return byWords(name, tags).orElseGet(() -> byName(name));
  }

  static Mascot byName(String name) {
    Mascot[] all = Mascot.values();
    return all[Math.floorMod(name == null ? 0 : name.hashCode(), all.length)];
  }

  /** 글 하나에 맞는 바탕들을 사전 순서대로. */
  private static List<Mascot> matchAll(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    String t = text.toLowerCase(Locale.ROOT);
    List<String> words = List.of(t.split("[^a-z0-9]+"));
    List<Mascot> out = new ArrayList<>();
    for (Map.Entry<Mascot, List<String>> e : WORDS.entrySet()) {
      for (String w : e.getValue()) {
        // 영어 단어는 다른 단어 안에 들어 있는 경우가 많아(education의 cat) 낱말 단위로만 맞춘다
        boolean hit = w.chars().allMatch(c -> c < 128) ? words.contains(w) : t.contains(w);
        if (hit) {
          out.add(e.getKey());
          break;
        }
      }
    }
    return out;
  }
}
