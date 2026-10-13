package com.blog.main.service;

import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.main.domain.RecentSearch;
import com.blog.main.repository.RecentSearchRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 최근 검색어 (T108, BRD-08). 본인만 보고, 최대 10개, 같은 검색어면 시각만 바꾼다. 하나씩 또는 전부 지운다. */
@Service
public class RecentSearchService {

  public static final int MAX = 10;

  public record RecentSearchRow(Long id, String keyword, LocalDateTime searchedAt) {}

  private final RecentSearchRepository repository;
  private final Clock clock;

  public RecentSearchService(RecentSearchRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /**
   * 검색어를 남긴다. 10개를 넘으면 오래된 것부터 지운다. keyword는 검사를 마친 2~20자.
   *
   * <p>searched_at은 초 단위라 같은 초에 여러 번 찾으면 시각이 같다. 같은 검색어는 지우고 다시 넣어 번호로도 가장 최근이 되게 한다.
   */
  @Transactional
  public void add(Long userId, String keyword) {
    repository.deleteKeyword(userId, keyword);
    repository.upsert(userId, keyword, LocalDateTime.now(clock));
    List<RecentSearch> all = repository.findByUserIdOrderBySearchedAtDescIdDesc(userId);
    if (all.size() > MAX) {
      repository.deleteByIds(
          all.subList(MAX, all.size()).stream().map(RecentSearch::getId).toList());
    }
  }

  @Transactional(readOnly = true)
  public List<RecentSearchRow> list(Long userId) {
    return repository.findByUserIdOrderBySearchedAtDescIdDesc(userId).stream()
        .limit(MAX)
        .map(r -> new RecentSearchRow(r.getId(), r.getKeyword(), r.getSearchedAt()))
        .toList();
  }

  /** 내 검색어 하나를 지운다. 남의 것이거나 없으면 404. */
  @Transactional
  public void delete(Long userId, Long id) {
    if (repository.deleteMine(id, userId) == 0) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
  }

  @Transactional
  public int deleteAll(Long userId) {
    return repository.deleteAllMine(userId);
  }
}
