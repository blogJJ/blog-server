package com.blog.board.service;

import com.blog.common.error.BusinessException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude 키가 없거나 호출이 실패했을 때 쓰는 단어 세기 태그 추천 (T167). 먼저 이 블로그에서 쓰던 태그 가운데 글에 나오는 것을, 다음으로 글에 두 번 이상
 * 나오는 낱말을 많이 나온 순서로 고른다. 낱말 뒤의 조사는 조사를 뗀 말이 글에 따로 나올 때만 뗀다('고양이가'와 '고양이'는 합치고 '고양이'를 '고양'으로 자르지
 * 않는다).
 */
final class KeywordTagSuggester {

  private static final Pattern WORD = Pattern.compile("[가-힣]+|[A-Za-z][A-Za-z0-9]*");

  // 길이가 긴 조사부터 본다
  private static final List<String> JOSA =
      List.of(
          "에서는", "으로는", "에게서", "이라는", "이라고", "에서도", "까지", "부터", "에서", "에게", "한테", "으로", "처럼", "보다",
          "이랑", "라는", "이나", "이고", "은", "는", "이", "가", "을", "를", "에", "의", "도", "와", "과", "로", "만",
          "랑");

  // 동사·형용사 끝처럼 보이면 태그로 쓰지 않는다
  private static final List<String> ENDINGS =
      List.of(
          "다", "요", "죠", "니까", "는데", "지만", "면서", "어서", "아서", "해서", "하게", "하고", "했고", "하는", "했던",
          "있는", "없는", "같은", "되는", "된", "한", "할", "하면", "해도", "니다", "습니", "어도", "으면");

  private static final Set<String> STOPWORDS =
      Set.of(
          "그리고", "그런데", "하지만", "그래서", "그러나", "또한", "정말", "너무", "진짜", "그냥", "많이", "조금", "아주", "매우",
          "오늘", "어제", "내일", "이번", "지난", "다음", "우리", "저는", "제가", "나는", "내가", "저희", "여러분", "이렇게",
          "그렇게", "어떻게", "이런", "그런", "저런", "이것", "그것", "저것", "여기", "거기", "때문", "경우", "정도", "하나",
          "생각", "사람", "부분", "모두", "다시", "먼저", "역시", "바로", "이제", "아직", "가장", "모든", "어떤", "무엇", "the",
          "and", "for", "with", "this", "that", "from", "are", "was", "you", "your", "have", "not",
          "but", "http", "https", "www", "com", "png", "jpg", "jpeg", "gif", "api", "images");

  private KeywordTagSuggester() {}

  /**
   * @param title 제목
   * @param plainBody 마크다운을 벗긴 본문
   * @param known 이 블로그에서 쓰던 태그(정리한 이름, 많이 쓴 순)
   * @param exclude 이미 단 태그(정리한 이름)
   * @param limit 몇 개까지
   */
  static List<String> suggest(
      String title,
      String plainBody,
      Collection<String> known,
      Collection<String> exclude,
      int limit) {
    String text = (title == null ? "" : title) + " " + (plainBody == null ? "" : plainBody);
    String lower = text.toLowerCase(Locale.ROOT);
    String lowerTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
    Set<String> picked = new LinkedHashSet<>(knownTagsInText(lower, lowerTitle, known, exclude));
    for (String word : frequentWords(lower, lowerTitle)) {
      if (picked.size() >= limit) {
        break;
      }
      String tag = normalizeOrNull(word);
      if (tag != null && !exclude.contains(tag)) {
        picked.add(tag);
      }
    }
    return picked.stream().limit(limit).toList();
  }

  /** 1) 쓰던 태그 가운데 글에 나오는 것. 많이 나온 순, 같으면 블로그에서 많이 쓴 순 */
  private static List<String> knownTagsInText(
      String lower, String lowerTitle, Collection<String> known, Collection<String> exclude) {
    Map<String, Integer> knownHits = new LinkedHashMap<>();
    for (String tag : known) {
      String word = tag.replace('_', ' ');
      int hits = count(lower, word) + (word.contains(" ") ? count(lower, tag) : 0);
      if (hits > 0 && !exclude.contains(tag)) {
        knownHits.put(tag, hits + (lowerTitle.contains(word) ? 2 : 0));
      }
    }
    return knownHits.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .map(Map.Entry::getKey)
        .toList();
  }

  /** 2) 글에 자주 나오는 낱말. 조사를 떼어 합치고 제목에 나오면 더 쳐서 많이 나온 순 */
  private static List<String> frequentWords(String lower, String lowerTitle) {
    Map<String, Integer> raw = new LinkedHashMap<>();
    Matcher m = WORD.matcher(lower);
    while (m.find()) {
      raw.merge(m.group(), 1, Integer::sum);
    }
    Map<String, Integer> merged = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> e : raw.entrySet()) {
      merged.merge(stem(e.getKey(), raw), e.getValue(), Integer::sum);
    }
    Map<String, Integer> titleBonus = new HashMap<>();
    Matcher tm = WORD.matcher(lowerTitle);
    while (tm.find()) {
      titleBonus.put(stem(tm.group(), raw), 2);
    }
    List<Map.Entry<String, Integer>> words = new ArrayList<>();
    for (Map.Entry<String, Integer> e : merged.entrySet()) {
      int score = e.getValue() + titleBonus.getOrDefault(e.getKey(), 0);
      if (score >= 2 && usable(e.getKey())) {
        words.add(Map.entry(e.getKey(), score));
      }
    }
    words.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
    return words.stream().map(Map.Entry::getKey).toList();
  }

  /** 조사를 뗀 말이 글에 따로 나오면 그 말로 합친다. */
  private static String stem(String token, Map<String, Integer> raw) {
    if (!token.matches("[가-힣]+")) {
      return token;
    }
    for (String josa : JOSA) {
      if (token.length() - josa.length() >= 2 && token.endsWith(josa)) {
        String base = token.substring(0, token.length() - josa.length());
        if (raw.containsKey(base)) {
          return base;
        }
      }
    }
    return token;
  }

  private static boolean usable(String word) {
    if (STOPWORDS.contains(word)) {
      return false;
    }
    if (word.matches("[가-힣]+")) {
      return word.length() >= 2
          && word.length() <= TagNormalizer.MAX_LENGTH
          && ENDINGS.stream().noneMatch(word::endsWith);
    }
    return word.length() >= 3 && word.length() <= TagNormalizer.MAX_LENGTH;
  }

  private static String normalizeOrNull(String word) {
    try {
      return TagNormalizer.normalize(word);
    } catch (BusinessException e) {
      return null;
    }
  }

  private static int count(String text, String word) {
    if (word.isEmpty()) {
      return 0;
    }
    int n = 0;
    for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + word.length())) {
      n++;
    }
    return n;
  }
}
