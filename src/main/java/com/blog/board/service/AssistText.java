package com.blog.board.service;

import java.util.regex.Pattern;

/**
 * 글쓰기 도우미(T167)에 보낼 글을 다듬는다. 태그 추천은 마크다운·HTML을 벗긴 글만 보고, 맞춤법 검사는 화면이 위치를 다시 찾을 수 있게 에디터 글을 그대로 보낸다.
 * 둘 다 길이를 잘라 비용을 묶는다.
 */
final class AssistText {

  private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?(```|$)|~~~.*?(~~~|$)");
  private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");
  private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
  private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
  private static final Pattern HTML_TAG = Pattern.compile("<[^>]{0,500}>");
  private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
  private static final Pattern ENTITY = Pattern.compile("&[a-zA-Z#0-9]{1,10};");
  private static final Pattern SYMBOLS = Pattern.compile("[#>*_~|`=\\[\\]()\\\\-]+");
  private static final Pattern SPACES = Pattern.compile("\\s+");

  private AssistText() {}

  /** 마크다운·HTML·주소·코드를 지운 읽을 글자만 남긴다. */
  static String plain(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return "";
    }
    String s = FENCED_CODE.matcher(markdown).replaceAll(" ");
    s = INLINE_CODE.matcher(s).replaceAll(" ");
    s = IMAGE.matcher(s).replaceAll(" ");
    s = LINK.matcher(s).replaceAll("$1");
    s = HTML_TAG.matcher(s).replaceAll(" ");
    s = URL.matcher(s).replaceAll(" ");
    s = ENTITY.matcher(s).replaceAll(" ");
    s = SYMBOLS.matcher(s).replaceAll(" ");
    return SPACES.matcher(s).replaceAll(" ").strip();
  }

  /** 앞에서부터 글자(코드 포인트) max개까지. 이모지 같은 두 칸짜리 글자를 반으로 자르지 않는다. */
  static String head(String text, int max) {
    if (text == null) {
      return "";
    }
    if (text.codePointCount(0, text.length()) <= max) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, max));
  }

  /** 글 안에 구분 태그를 흉내 낸 글자가 있어도 블록을 닫지 못하게 꺾쇠를 바꾼다. */
  static String fence(String text, String tag) {
    return text.replace("<" + tag, "‹" + tag).replace("</" + tag, "‹/" + tag);
  }
}
