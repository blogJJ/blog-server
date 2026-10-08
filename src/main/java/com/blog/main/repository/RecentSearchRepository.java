package com.blog.main.repository;

import com.blog.main.domain.RecentSearch;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecentSearchRepository extends JpaRepository<RecentSearch, Long> {

  /** 새 검색어면 넣고, 이미 있으면 시각만 바꾼다. */
  @Modifying
  @Query(
      value =
          "insert into recent_searches (user_id, keyword, searched_at) values (:userId, :keyword, :now)"
              + " on duplicate key update searched_at = :now",
      nativeQuery = true)
  void upsert(
      @Param("userId") Long userId,
      @Param("keyword") String keyword,
      @Param("now") LocalDateTime now);

  List<RecentSearch> findByUserIdOrderBySearchedAtDescIdDesc(Long userId);

  @Modifying
  @Query("delete from RecentSearch r where r.id = :id and r.user.id = :userId")
  int deleteMine(@Param("id") Long id, @Param("userId") Long userId);

  @Modifying
  @Query("delete from RecentSearch r where r.user.id = :userId")
  int deleteAllMine(@Param("userId") Long userId);

  @Modifying
  @Query("delete from RecentSearch r where r.id in :ids")
  int deleteByIds(@Param("ids") List<Long> ids);
}
