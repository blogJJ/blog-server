package com.blog.blog.service;

import com.blog.blog.domain.CoverScene;
import com.blog.blog.domain.CoverScene.Prop;
import com.blog.blog.domain.CoverScene.Scene;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 태그 단어 사전으로 대표 픽셀 장면과 소품 주제를 고른다 (BLG-15, D-129). Claude가 못 고를 때 쓴다. 태그를 앞에서부터 보고 사전 단어가 들어 있으면 그
 * 장면·주제, 하나도 없으면 빈 값이라 바탕 그림으로 정한다(MascotService.sceneFor).
 */
public final class KeywordScenePicker {

  private static final Map<Scene, List<String>> SCENES = new LinkedHashMap<>();
  private static final Map<Prop, List<String>> PROPS = new LinkedHashMap<>();

  static {
    // 뜻이 좁은 장면을 먼저 본다. '독서모임'은 서재보다 도서관, '꽃집'은 정원보다 꽃집
    SCENES.put(Scene.LIBRARY, List.of("도서관", "독서모임", "서평", "북리뷰", "인문", "library"));
    SCENES.put(Scene.CAMPSITE, List.of("캠핑", "차박", "글램핑", "백패킹", "불멍", "camp", "camping"));
    SCENES.put(
        Scene.MOUNTAIN, List.of("등산", "산행", "트레킹", "하이킹", "백패커", "hiking", "trekking", "mountain"));
    SCENES.put(Scene.TRAIN, List.of("기차", "열차", "철도", "ktx", "내일로", "국내여행", "train", "rail"));
    SCENES.put(
        Scene.AQUARIUM, List.of("수족관", "아쿠아리움", "물고기", "어항", "열대어", "물생활", "aquarium", "fish"));
    SCENES.put(
        Scene.ATELIER,
        List.of("그림", "드로잉", "일러스트", "수채화", "미술", "화실", "웹툰", "drawing", "art", "illustration"));
    SCENES.put(
        Scene.WORKSHOP,
        List.of("공방", "목공", "diy", "만들기", "공예", "핸드메이드", "뜨개", "도자기", "리폼", "craft", "handmade"));
    SCENES.put(Scene.FLOWER_SHOP, List.of("꽃", "플라워", "florist", "flower", "flowers"));
    SCENES.put(
        Scene.OBSERVATORY,
        List.of("별자리", "별보기", "천문", "우주", "천체", "망원경", "밤하늘", "astronomy", "space"));
    SCENES.put(Scene.BEDROOM, List.of("자취", "방꾸미기", "인테리어", "원룸", "홈스타일링", "집꾸미기", "interior"));
    SCENES.put(
        Scene.STREET_MARKET,
        List.of("시장", "길거리", "노점", "야시장", "분식", "먹거리", "market", "streetfood"));
    SCENES.put(
        Scene.RIVERSIDE,
        List.of("강변", "한강", "낚시", "자전거", "라이딩", "피크닉", "river", "fishing", "cycling"));
    SCENES.put(
        Scene.GREENHOUSE,
        List.of("식물", "반려식물", "다육", "가드닝", "화분", "플랜테리어", "온실", "plant", "plants", "gardening"));
    SCENES.put(Scene.ROOFTOP, List.of("옥상", "텃밭", "베란다", "도시농업", "rooftop"));
    // 처음부터 있던 장면
    SCENES.put(Scene.DESK, List.of("개발", "코딩", "프로그래밍", "백엔드", "프론트", "알고리즘", "it", "dev", "code"));
    SCENES.put(Scene.GAME, List.of("게임", "닌텐도", "플스", "game"));
    SCENES.put(Scene.PHOTO, List.of("사진", "카메라", "출사", "photo", "camera"));
    SCENES.put(Scene.STAGE, List.of("음악", "노래", "밴드", "기타", "피아노", "music", "song"));
    SCENES.put(Scene.BAKERY, List.of("베이킹", "빵", "디저트", "케이크", "baking", "dessert"));
    SCENES.put(Scene.CAFE, List.of("카페", "커피", "coffee", "cafe"));
    SCENES.put(Scene.KITCHEN, List.of("요리", "레시피", "집밥", "음식", "맛집", "food", "cook", "recipe"));
    SCENES.put(Scene.SEA, List.of("바다", "해외", "해변", "서핑", "섬", "beach", "sea"));
    SCENES.put(Scene.SKY, List.of("비행", "항공", "여행", "travel", "trip", "flight"));
    SCENES.put(Scene.STUDY, List.of("공부", "스터디", "자격증", "시험", "독서", "책", "글쓰기", "study", "book"));
    SCENES.put(
        Scene.SNOWFIELD, List.of("운동", "헬스", "스키", "겨울", "러닝", "다이어트", "sport", "fitness", "ski"));
    SCENES.put(Scene.PARK, List.of("산책", "반려", "강아지", "펫", "dog", "pet", "walk"));
    SCENES.put(Scene.GARDEN, List.of("일상", "일기", "감성", "고양이", "daily", "diary"));
    SCENES.put(Scene.NIGHT_CITY, List.of("재테크", "주식", "경제", "도시", "city"));

    PROPS.put(Prop.CAT, List.of("고양이", "냥", "cat"));
    PROPS.put(Prop.DOG, List.of("강아지", "반려견", "댕댕", "dog", "puppy"));
    PROPS.put(Prop.BIRD, List.of("앵무", "탐조", "새덕후", "bird"));
    PROPS.put(Prop.FLOWER, List.of("꽃", "flower", "flowers"));
    PROPS.put(Prop.PLANT, List.of("식물", "다육", "화분", "가드닝", "텃밭", "plant", "plants"));
    PROPS.put(Prop.CODE, List.of("개발", "코딩", "프로그래밍", "dev", "code"));
    PROPS.put(Prop.GAME, List.of("게임", "game"));
    PROPS.put(Prop.MUSIC, List.of("음악", "노래", "밴드", "기타", "피아노", "music"));
    PROPS.put(Prop.ART, List.of("그림", "드로잉", "미술", "일러스트", "공예", "art", "drawing"));
    PROPS.put(Prop.BOOK, List.of("책", "독서", "서평", "소설", "공부", "book", "novel"));
    PROPS.put(Prop.COFFEE, List.of("커피", "카페", "홍차", "녹차", "티타임", "coffee", "tea"));
    PROPS.put(Prop.FOOD, List.of("요리", "맛집", "음식", "레시피", "먹방", "베이킹", "food", "recipe"));
    PROPS.put(Prop.SPORT, List.of("운동", "헬스", "러닝", "축구", "야구", "수영", "등산", "sport", "fitness"));
    PROPS.put(Prop.STAR, List.of("별자리", "천문", "우주", "밤하늘", "space"));
    PROPS.put(Prop.TRAVEL, List.of("여행", "해외", "캠핑", "기차", "travel", "trip"));
  }

  private KeywordScenePicker() {}

  /** 태그(다음으로 블로그 이름)에 맞는 장면. */
  public static Optional<Scene> scene(String name, List<String> tags) {
    return byWords(SCENES, name, tags);
  }

  /** 태그(다음으로 블로그 이름)에 맞는 소품 주제. */
  public static Optional<Prop> prop(String name, List<String> tags) {
    return byWords(PROPS, name, tags);
  }

  /** 사전에 있는 장면 단어. 테스트가 모든 장면에 단어가 있는지 본다. */
  static List<String> wordsFor(CoverScene.Scene scene) {
    return SCENES.getOrDefault(scene, List.of());
  }

  private static <E> Optional<E> byWords(
      Map<E, List<String>> dict, String name, List<String> tags) {
    for (String tag : tags) {
      Optional<E> m = match(dict, tag);
      if (m.isPresent()) {
        return m;
      }
    }
    return match(dict, name);
  }

  private static <E> Optional<E> match(Map<E, List<String>> dict, String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    String t = text.toLowerCase(Locale.ROOT);
    List<String> words = List.of(t.split("[^a-z0-9]+"));
    for (Map.Entry<E, List<String>> e : dict.entrySet()) {
      for (String w : e.getValue()) {
        // 영어 단어는 낱말 단위로만 맞춘다(KeywordMascotPicker와 같은 규칙)
        boolean hit = w.chars().allMatch(c -> c < 128) ? words.contains(w) : t.contains(w);
        if (hit) {
          return Optional.of(e.getKey());
        }
      }
    }
    return Optional.empty();
  }
}
