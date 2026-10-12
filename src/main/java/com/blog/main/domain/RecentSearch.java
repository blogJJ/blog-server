package com.blog.main.domain;

import com.blog.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/** 최근 검색어 (recent_searches, T100, BRD-08). 본인만 보고, 최대 10개, 같은 검색어면 시각만 바꾼다. */
@Entity
@Table(
    name = "recent_searches",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_recent_search",
            columnNames = {"user_id", "keyword"}))
public class RecentSearch {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(nullable = false, length = 20)
  private String keyword;

  @Column(name = "searched_at", nullable = false)
  private LocalDateTime searchedAt;

  protected RecentSearch() {}

  public Long getId() {
    return id;
  }

  public User getUser() {
    return user;
  }

  public String getKeyword() {
    return keyword;
  }

  public LocalDateTime getSearchedAt() {
    return searchedAt;
  }
}
