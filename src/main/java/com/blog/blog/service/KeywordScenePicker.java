package com.blog.blog.service;

import com.blog.blog.domain.CoverScene;
import com.blog.blog.domain.CoverScene.Backdrop;
import com.blog.blog.domain.CoverScene.Event;
import com.blog.blog.domain.CoverScene.Prop;
import com.blog.blog.domain.CoverScene.Scene;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 태그 단어 사전으로 대표 픽셀 장면과 소품 주제, 섞기 모드의 배경과 계절·행사를 고른다 (BLG-15, D-129). 장면·주제는 Claude가 못 고를 때 쓴다. 태그를
 * 앞에서부터 보고 사전 단어가 들어 있으면 그 장면·주제, 하나도 없으면 빈 값이라 바탕 그림으로 정한다(MascotService.sceneFor). 배경과 계절도 맞는 단어가
 * 없으면 빈 값이고, 그때는 MascotService가 무작위로 섞는다.
 */
public final class KeywordScenePicker {

  private static final Map<Scene, List<String>> SCENES = new LinkedHashMap<>();
  private static final Map<Prop, List<String>> PROPS = new LinkedHashMap<>();
  private static final Map<Backdrop, List<String>> BACKDROPS = new LinkedHashMap<>();
  private static final Map<Event, List<String>> EVENTS = new LinkedHashMap<>();
  private static final List<String> GENERIC_SPORT = new ArrayList<>();
  private static final Map<String, String> REWRITE = new LinkedHashMap<>();

  /** 종목이 드러나지 않는 운동 단어('운동', '스포츠')가 고르는 장면들. 블로그마다 무작위로 하나 (T158 후속). */
  public static final List<Scene> SPORT_POOL =
      List.of(
          Scene.GYM,
          Scene.SOCCER,
          Scene.TRACK,
          Scene.POOL,
          Scene.COURT,
          Scene.BASEBALL,
          Scene.PARK,
          Scene.RIVERSIDE,
          Scene.MOUNTAIN);

  // '운동' 뒤에 종목 단어가 오면 그 종목을 따른다('운동, 축구'는 축구장)
  private static final Set<Scene> SPORT_SCENES =
      EnumSet.of(
          Scene.GYM,
          Scene.SOCCER,
          Scene.TRACK,
          Scene.POOL,
          Scene.COURT,
          Scene.BASEBALL,
          Scene.SNOWFIELD,
          Scene.MOUNTAIN,
          Scene.RIVERSIDE);

  // 다시 고르기 대상인 운동 태그(T158 후속): 운동 종목·헬스·겨울 운동 장면의 단어와 종목 없는 운동 단어
  private static final Set<Scene> SPORT_WORD_SCENES =
      EnumSet.of(
          Scene.GYM,
          Scene.SOCCER,
          Scene.TRACK,
          Scene.POOL,
          Scene.COURT,
          Scene.BASEBALL,
          Scene.SNOWFIELD);

  static {
    // 뜻이 좁은 장면을 먼저 본다. '독서모임'은 서재보다 도서관, '꽃집'은 정원보다 꽃집
    // 운동 종목(T158 후속). 종목이 드러나는 단어라 가장 먼저 본다. '트랙'은 사운드트랙, '수영'은 수영구(REWRITE)에도 있어 넣지 않거나 바꿔 본다
    SCENES.put(
        Scene.SOCCER,
        List.of("축구", "풋살", "월드컵", "k리그", "프리미어리그", "soccer", "football", "futsal", "epl"));
    SCENES.put(Scene.BASEBALL, List.of("야구", "kbo", "mlb", "baseball"));
    SCENES.put(Scene.COURT, List.of("농구", "nba", "kbl", "basketball"));
    SCENES.put(Scene.POOL, List.of("수영", "swim", "swimming"));
    SCENES.put(
        Scene.TRACK,
        List.of(
            "러닝",
            "런닝",
            "마라톤",
            "조깅",
            "달리기",
            "육상",
            "running",
            "marathon",
            "jogging",
            "run",
            "runner"));
    // D-129 뒤에 더한 장면. 뜻이 좁아 앞에서 본다
    SCENES.put(
        Scene.SPACE_STATION, List.of("우주정거장", "우주여행", "로켓", "nasa", "sf", "scifi", "rocket"));
    SCENES.put(
        Scene.RECORD_SHOP, List.of("레코드", "바이닐", "턴테이블", "음반", "lp", "vinyl", "record", "records"));
    SCENES.put(
        Scene.POJANGMACHA,
        List.of("포장마차", "포차", "혼술", "소주", "막걸리", "맥주", "야식", "안주", "soju", "beer"));
    SCENES.put(
        Scene.CONVENIENCE, List.of("편의점", "라면", "간식", "과자", "신상", "snack", "snacks", "ramen"));
    SCENES.put(Scene.LAUNDROMAT, List.of("빨래", "세탁", "살림", "대청소", "정리정돈", "laundry", "cleaning"));
    SCENES.put(
        Scene.GYM,
        List.of(
            "헬스", "피트니스", "웨이트", "필라테스", "요가", "크로스핏", "다이어트", "홈트", "바디프로필", "gym", "fitness",
            "yoga", "workout", "pilates"));
    SCENES.put(
        Scene.OFFICE,
        List.of("직장", "회사", "업무", "마케팅", "커리어", "취업", "이직", "기획", "office", "career", "work"));
    SCENES.put(Scene.SUBWAY, List.of("지하철", "출퇴근", "출근", "통근", "subway", "metro", "commute"));
    // '버스킹'은 음악이라 '버스'만으로는 고르지 않는다
    SCENES.put(Scene.BUS_STOP, List.of("정류장", "시내버스", "버스여행", "기다림", "bus"));
    SCENES.put(
        Scene.HANOK,
        List.of("한옥", "전통", "역사", "고궁", "한복", "국악", "다도", "경주", "전주", "hanok", "history"));
    SCENES.put(Scene.FARM, List.of("농장", "농사", "귀농", "귀촌", "시골", "주말농장", "farm", "farming"));
    SCENES.put(
        Scene.CLASSROOM,
        List.of("교실", "학교", "선생님", "교사", "수업", "교육", "학원", "school", "teacher", "class"));
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
    SCENES.put(Scene.STAGE, List.of("음악", "노래", "밴드", "기타", "피아노", "버스킹", "music", "song"));
    SCENES.put(Scene.BAKERY, List.of("베이킹", "빵", "디저트", "케이크", "baking", "dessert"));
    SCENES.put(Scene.CAFE, List.of("카페", "커피", "coffee", "cafe"));
    SCENES.put(
        Scene.KITCHEN, List.of("요리", "레시피", "집밥", "음식", "맛집", "건강식", "food", "cook", "recipe"));
    SCENES.put(Scene.SEA, List.of("바다", "해외", "해변", "서핑", "섬", "beach", "sea"));
    SCENES.put(Scene.SKY, List.of("비행", "항공", "여행", "travel", "trip", "flight"));
    SCENES.put(Scene.STUDY, List.of("공부", "스터디", "자격증", "시험", "독서", "책", "글쓰기", "study", "book"));
    // 겨울 운동만 눈밭이다. 예전처럼 바다 뒤에서 본다('겨울바다'는 바다)
    SCENES.put(
        Scene.SNOWFIELD,
        List.of(
            "스키",
            "스노보드",
            "겨울",
            "스케이트",
            "스케이팅",
            "썰매",
            "ski",
            "skiing",
            "snowboard",
            "winter",
            "skating"));
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
    PROPS.put(
        Prop.SPORT,
        List.of(
            "운동", "헬스", "러닝", "축구", "야구", "수영", "농구", "마라톤", "테니스", "스포츠", "등산", "sport", "sports",
            "fitness"));

    // 어느 종목인지 드러나지 않는 운동 단어. 운동 장면 몇 개(SPORT_POOL) 중 하나를 블로그마다 무작위로 고른다
    GENERIC_SPORT.addAll(
        List.of(
            "운동",
            "스포츠",
            "체육",
            "건강",
            "테니스",
            "배드민턴",
            "배구",
            "탁구",
            "골프",
            "볼링",
            "클라이밍",
            "복싱",
            "태권도",
            "주짓수",
            "격투기",
            "스쿼시",
            "sport",
            "sports",
            "exercise",
            "health",
            "tennis",
            "golf",
            "badminton",
            "volleyball",
            "climbing",
            "boxing"));

    // 운동 단어가 들어 있지만 뜻이 다른 말. 맞추기 전에 바꿔 둔다('머신러닝'은 러닝이 아니다)
    REWRITE.put("머신러닝", "개발");
    REWRITE.put("딥러닝", "개발");
    REWRITE.put("e러닝", "교육");
    REWRITE.put("이러닝", "교육");
    REWRITE.put("러닝머신", "헬스");
    REWRITE.put("위스키", "술");
    REWRITE.put("스키니", "패션");
    REWRITE.put("스케이트보드", "보드");
    REWRITE.put("수영구", "부산");
    REWRITE.put("독립운동", "역사");
    REWRITE.put("민주화운동", "역사");
    REWRITE.put("학생운동", "역사");
    REWRITE.put("사회운동", "사회");
    PROPS.put(Prop.STAR, List.of("별자리", "천문", "우주", "밤하늘", "space"));
    PROPS.put(Prop.TRAVEL, List.of("여행", "해외", "캠핑", "기차", "travel", "trip"));

    // 섞기 모드의 배경. 장소가 드러나는 단어가 먼저다(여행은 항구, 다이빙은 바닷속)
    // D-129 확장(T158)에서 더한 배경. 뜻이 좁아 앞에서 본다('한강 야경'은 야경 빌딩보다 한강 다리).
    // 한국어는 글자 포함으로 맞추므로 '성'(감성), '밀'(비밀), '다리'처럼 짧아 다른 말에 섞이는 단어는 넣지 않는다
    BACKDROPS.put(Backdrop.LAVA, List.of("화산", "용암", "하와이", "volcano", "lava", "hawaii"));
    BACKDROPS.put(Backdrop.FALLS, List.of("폭포", "계곡", "waterfall", "falls"));
    BACKDROPS.put(Backdrop.SANTO, List.of("산토리니", "그리스", "지중해", "santorini", "greece", "greek"));
    BACKDROPS.put(Backdrop.MILLS, List.of("풍력", "풍차", "대관령", "windmill", "wind"));
    BACKDROPS.put(Backdrop.LILAC, List.of("라벤더", "프로방스", "lavender", "provence"));
    BACKDROPS.put(
        Backdrop.PALMS, List.of("야자수", "휴양지", "하와이안", "노을", "몰디브", "palm", "sunset", "resort"));
    BACKDROPS.put(Backdrop.STORM, List.of("등대", "폭풍", "태풍", "lighthouse", "storm"));
    BACKDROPS.put(Backdrop.ROOFS, List.of("한옥마을", "기와", "북촌", "전주한옥", "hanokvillage"));
    BACKDROPS.put(Backdrop.BRIDGE, List.of("한강", "한강다리", "bridge", "hanriver"));
    BACKDROPS.put(Backdrop.WHEAT, List.of("밀밭", "보리밭", "곡식", "추수", "wheat", "harvest"));
    BACKDROPS.put(
        Backdrop.BERG, List.of("빙하", "빙산", "남극", "북극", "알래스카", "glacier", "antarctica", "alaska"));
    BACKDROPS.put(Backdrop.BAMBOO, List.of("대나무", "죽녹원", "담양", "bamboo"));
    BACKDROPS.put(
        Backdrop.CASTLE,
        List.of("고성", "성곽", "궁전", "동화", "유럽", "디즈니", "castle", "palace", "europe"));
    BACKDROPS.put(Backdrop.UNDER, List.of("다이빙", "스쿠버", "스노클링", "바닷속", "해양", "diving", "scuba"));
    BACKDROPS.put(
        Backdrop.AURORA, List.of("오로라", "북유럽", "아이슬란드", "핀란드", "노르웨이", "aurora", "iceland"));
    BACKDROPS.put(Backdrop.DESERT, List.of("사막", "사하라", "이집트", "중동", "desert"));
    BACKDROPS.put(Backdrop.SPACE, List.of("우주", "로켓", "행성", "sf", "space", "rocket"));
    BACKDROPS.put(Backdrop.SAKURA, List.of("벚꽃", "꽃놀이", "일본", "교토", "sakura", "japan"));
    BACKDROPS.put(Backdrop.AUTUMN, List.of("단풍", "가을", "autumn"));
    BACKDROPS.put(Backdrop.TULIP, List.of("튤립", "네덜란드", "꽃밭", "tulip"));
    BACKDROPS.put(
        Backdrop.JUNGLE,
        List.of("정글", "동남아", "열대", "발리", "태국", "베트남", "jungle", "tropical", "bali"));
    BACKDROPS.put(Backdrop.CANYON, List.of("협곡", "캐니언", "로드트립", "미국여행", "canyon", "roadtrip"));
    BACKDROPS.put(Backdrop.RICE, List.of("시골", "농촌", "귀농", "귀촌", "전원", "countryside"));
    BACKDROPS.put(Backdrop.HARBOR, List.of("여행", "항구", "부산", "크루즈", "travel", "trip", "port"));
    BACKDROPS.put(Backdrop.SEA, List.of("바다", "해변", "제주", "서핑", "beach", "sea", "ocean", "surf"));
    BACKDROPS.put(Backdrop.MOUNT, List.of("등산", "설산", "알프스", "스위스", "히말라야", "hiking", "alps"));
    BACKDROPS.put(Backdrop.SNOW, List.of("겨울", "스키", "스노보드", "winter", "ski"));
    BACKDROPS.put(Backdrop.LAKE, List.of("호수", "낚시", "캠핑", "lake", "fishing"));
    BACKDROPS.put(
        Backdrop.TOWERS, List.of("야경", "서울", "뉴욕", "재테크", "주식", "nightview", "seoul", "newyork"));
    BACKDROPS.put(Backdrop.TREES, List.of("숲", "산책", "forest", "walk"));
    BACKDROPS.put(Backdrop.STREET, List.of("골목", "거리", "카페거리", "street"));
    BACKDROPS.put(Backdrop.CITY, List.of("도시", "city"));

    // 계절·행사 장식. 크리스마스·핼러윈·생일은 태그에 있을 때만 붙는다
    EVENTS.put(Event.XMAS, List.of("크리스마스", "성탄", "산타", "연말", "christmas", "xmas"));
    EVENTS.put(Event.BOO, List.of("할로윈", "핼러윈", "halloween"));
    EVENTS.put(Event.BDAY, List.of("생일", "파티", "기념일", "birthday", "party"));
    EVENTS.put(Event.FLIES, List.of("반딧불", "여름밤", "firefly", "fireflies"));
    EVENTS.put(Event.BLOOM, List.of("벚꽃", "봄", "꽃놀이", "spring"));
    EVENTS.put(Event.SUN, List.of("여름", "휴가", "바캉스", "물놀이", "summer", "vacation"));
    EVENTS.put(Event.FALL, List.of("가을", "단풍", "autumn"));
    EVENTS.put(Event.FROST, List.of("겨울", "눈사람", "스키", "winter", "snow"));
    EVENTS.put(Event.WET, List.of("장마", "빗소리", "우산", "rain", "rainy"));
  }

  private KeywordScenePicker() {}

  /** 태그(다음으로 블로그 이름)에 맞는 장면. 종목 없는 운동 단어는 이름·태그로 정한 수로 운동 장면 하나를 고른다. */
  public static Optional<Scene> scene(String name, List<String> tags) {
    return scene(name, tags, Objects.hash(name, tags));
  }

  /**
   * 태그(다음으로 블로그 이름)에 맞는 장면. 태그를 앞에서부터 보고 사전 단어가 있으면 그 장면이다. 종목 없는 운동 단어('운동', '스포츠')면 뒤 태그에 종목 단어가
   * 있을 때 그 종목, 없으면 {@code pick}으로 {@link #SPORT_POOL}에서 하나를 고른다 (T158 후속).
   *
   * @param pick 블로그의 무작위 수. 같은 수면 같은 장면
   */
  public static Optional<Scene> scene(String name, List<String> tags, int pick) {
    List<String> texts = new ArrayList<>(tags);
    texts.add(name);
    for (int i = 0; i < texts.size(); i++) {
      Optional<Scene> m = match(SCENES, texts.get(i));
      if (m.isPresent()) {
        return m;
      }
      if (hasAny(GENERIC_SPORT, texts.get(i))) {
        for (String later : texts.subList(i + 1, texts.size())) {
          Optional<Scene> s = match(SCENES, later).filter(SPORT_SCENES::contains);
          if (s.isPresent()) {
            return s;
          }
        }
        return Optional.of(SPORT_POOL.get(Math.floorMod(pick, SPORT_POOL.size())));
      }
    }
    return Optional.empty();
  }

  /** 이 이름·태그로 {@link #scene(String, List, int)}이 고를 수 있는 장면 모두. 맞는 단어가 없으면 빈 집합. */
  public static Set<Scene> possibleScenes(String name, List<String> tags) {
    Set<Scene> out = EnumSet.noneOf(Scene.class);
    for (int pick = 0; pick < SPORT_POOL.size(); pick++) {
      scene(name, tags, pick).ifPresent(out::add);
    }
    return out;
  }

  /** 태그에 운동 단어(종목, 헬스·요가, 겨울 운동, '운동'·'스포츠')가 있다. */
  public static boolean hasSportWord(List<String> tags) {
    for (String tag : tags) {
      if (hasAny(GENERIC_SPORT, tag)
          || match(SCENES, tag).filter(SPORT_WORD_SCENES::contains).isPresent()) {
        return true;
      }
    }
    return false;
  }

  /** 태그에 겨울 운동 단어(스키, 스노보드, 겨울, 스케이트)가 있다. */
  public static boolean hasWinterWord(List<String> tags) {
    for (String tag : tags) {
      if (hasAny(SCENES.get(Scene.SNOWFIELD), tag)) {
        return true;
      }
    }
    return false;
  }

  /**
   * DB에서 운동 태그가 있는 블로그를 먼저 거를 때 쓰는 MySQL 정규식. 영어 낱말 단위나 '머신러닝' 같은 예외는 거르지 않으니 {@link
   * #hasSportWord}로 다시 본다.
   */
  public static String sportPattern() {
    List<String> words = new ArrayList<>(GENERIC_SPORT);
    SPORT_WORD_SCENES.forEach(s -> words.addAll(SCENES.get(s)));
    return String.join("|", words);
  }

  /** 태그(다음으로 블로그 이름)에 맞는 소품 주제. */
  public static Optional<Prop> prop(String name, List<String> tags) {
    return byWords(PROPS, name, tags);
  }

  /** 태그(다음으로 블로그 이름)에 맞는 섞기 모드 배경. */
  public static Optional<Backdrop> backdrop(String name, List<String> tags) {
    return byWords(BACKDROPS, name, tags);
  }

  /** 태그(다음으로 블로그 이름)에 맞는 계절·행사 장식. */
  public static Optional<Event> event(String name, List<String> tags) {
    return byWords(EVENTS, name, tags);
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
    String t = normalize(text);
    List<String> words = List.of(t.split("[^a-z0-9]+"));
    for (Map.Entry<E, List<String>> e : dict.entrySet()) {
      for (String w : e.getValue()) {
        if (hit(t, words, w)) {
          return Optional.of(e.getKey());
        }
      }
    }
    return Optional.empty();
  }

  private static boolean hasAny(List<String> dict, String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String t = normalize(text);
    List<String> words = List.of(t.split("[^a-z0-9]+"));
    return dict.stream().anyMatch(w -> hit(t, words, w));
  }

  private static String normalize(String text) {
    String t = text.toLowerCase(Locale.ROOT);
    for (Map.Entry<String, String> r : REWRITE.entrySet()) {
      t = t.replace(r.getKey(), r.getValue());
    }
    return t;
  }

  // 영어 단어는 낱말 단위로만 맞춘다(KeywordMascotPicker와 같은 규칙)
  private static boolean hit(String t, List<String> words, String w) {
    return w.chars().allMatch(c -> c < 128) ? words.contains(w) : t.contains(w);
  }
}
