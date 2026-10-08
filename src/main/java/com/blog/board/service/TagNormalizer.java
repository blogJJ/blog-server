package com.blog.board.service;

import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 태그 정리 규칙 (T043, BRD-04, D-88, D-98). 블로그와 글이 같이 쓴다.
 *
 * <ul>
 *   <li>앞의 #과 앞뒤 공백을 지우고, 가운데 공백은 {@code _}로 바꾸고, 영문은 소문자로.
 *   <li>1~20자 한글·영문·숫자·{@code _}. 빈 태그는 버린다.
 *   <li>같은 태그는 하나로 합치고, 10개까지. 금칙어는 없다.
 * </ul>
 */
public final class TagNormalizer {

  public static final int MAX_TAGS = 10;
  public static final int MAX_LENGTH = 20;
  private static final Pattern ALLOWED = Pattern.compile("^[가-힣A-Za-z0-9_]+$");

  private TagNormalizer() {}

  /** 하나를 정리한다. 비면 null. 규칙에 맞지 않으면 INVALID_INPUT. */
  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String tag = raw.strip();
    while (tag.startsWith("#")) {
      tag = tag.substring(1).strip();
    }
    if (tag.isEmpty()) {
      return null;
    }
    tag = tag.replaceAll("\\s+", "_").toLowerCase(Locale.ROOT);
    if (tag.length() > MAX_LENGTH || !ALLOWED.matcher(tag).matches()) {
      throw new BusinessException(
          ErrorCode.INVALID_INPUT, "태그는 1~20자 한글, 영문, 숫자, _로 입력해 주세요: " + raw.strip());
    }
    return tag;
  }

  /** 목록을 정리한다. 겹치는 태그는 합치고, 정리한 뒤 10개를 넘으면 INVALID_INPUT. */
  public static List<String> normalizeAll(List<String> raws) {
    if (raws == null) {
      return List.of();
    }
    Set<String> tags = new LinkedHashSet<>();
    for (String raw : raws) {
      String tag = normalize(raw);
      if (tag != null) {
        tags.add(tag);
      }
    }
    if (tags.size() > MAX_TAGS) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "태그는 10개까지 달 수 있어요.");
    }
    return new ArrayList<>(tags);
  }
}
