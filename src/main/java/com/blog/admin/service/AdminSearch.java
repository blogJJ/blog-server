package com.blog.admin.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** 관리자 목록 검색 공통 */
public final class AdminSearch {

  public static final int PAGE_SIZE = 20;
  public static final int QUERY_MAX = 50;

  private AdminSearch() {}

  /** 검색어를 LIKE 패턴 {@code %q%}로. '!'를 이스케이프 문자로 쓴다. 비었으면 null(전체) */
  public static String like(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String q = raw.strip();
    if (q.length() > QUERY_MAX) {
      q = q.substring(0, QUERY_MAX);
    }
    return "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  /** page는 1부터 */
  public static Pageable page(int page) {
    return PageRequest.of(Math.max(page, 1) - 1, PAGE_SIZE);
  }
}
