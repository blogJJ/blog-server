package com.blog.main.api;

import com.blog.common.security.AuthUser;
import com.blog.main.service.RecentSearchService;
import com.blog.main.service.RecentSearchService.RecentSearchRow;
import com.blog.main.service.SearchService;
import com.blog.main.service.SearchService.SearchResult;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 통합 검색과 최근 검색어 (T107, T108, BRD-08). 검색은 비회원도, 최근 검색어는 본인만. */
@RestController
public class SearchController {

  private final SearchService searchService;
  private final RecentSearchService recentSearchService;

  public SearchController(SearchService searchService, RecentSearchService recentSearchService) {
    this.searchService = searchService;
    this.recentSearchService = recentSearchService;
  }

  @GetMapping("/api/search")
  public SearchResult search(
      @AuthenticationPrincipal AuthUser user,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "post") String type,
      @RequestParam(defaultValue = "relevance") String sort,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(defaultValue = "1") int page) {
    return searchService.search(q, type, sort, user == null ? null : user.id(), size, page);
  }

  @GetMapping("/api/me/recent-searches")
  public List<RecentSearchRow> recent(@AuthenticationPrincipal AuthUser user) {
    return recentSearchService.list(user.id());
  }

  @DeleteMapping("/api/me/recent-searches/{id}")
  public ResponseEntity<Void> deleteOne(
      @AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
    recentSearchService.delete(user.id(), id);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/api/me/recent-searches")
  public Map<String, Integer> deleteAll(@AuthenticationPrincipal AuthUser user) {
    return Map.of("deleted", recentSearchService.deleteAll(user.id()));
  }
}
