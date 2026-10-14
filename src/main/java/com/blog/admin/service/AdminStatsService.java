package com.blog.admin.service;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 통계 (T124, ADM-05): 회원 수, 블로그 수, 일별 가입·글 수.
 *
 * <ul>
 *   <li>회원 수: 탈퇴하지 않은 일반 회원(관리자 계정 제외). 블로그 수: 폐쇄되지 않은 블로그(폐쇄 예정 포함).
 *   <li>일별: 오늘을 포함한 최근 days일(7~90, 기본 30). 그날 가입한 회원 수와 그날 쓴 글 수(나중에 지운 글 포함). 없는 날은 0.
 * </ul>
 */
@Service
public class AdminStatsService {

  public static final int DEFAULT_DAYS = 30;
  public static final int MIN_DAYS = 7;
  public static final int MAX_DAYS = 90;

  public record Daily(LocalDate date, long signups, long posts) {}

  public record Stats(long userCount, long blogCount, List<Daily> daily) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public AdminStatsService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Stats stats(Integer days) {
    int span = days == null ? DEFAULT_DAYS : Math.clamp(days, MIN_DAYS, MAX_DAYS);
    LocalDate today = LocalDate.now(clock);
    LocalDate from = today.minusDays(span - 1L);

    Long users =
        jdbc.queryForObject(
            "select count(*) from users where status = 'ACTIVE' and role = 'USER'", Long.class);
    Long blogs =
        jdbc.queryForObject("select count(*) from blogs where status <> 'CLOSED'", Long.class);
    Map<LocalDate, Long> signups =
        perDay(
            "select date(created_at) d, count(*) c from users where created_at >= ?"
                + " and role = 'USER' group by date(created_at)",
            from);
    Map<LocalDate, Long> posts =
        perDay(
            "select date(created_at) d, count(*) c from posts where created_at >= ?"
                + " group by date(created_at)",
            from);

    List<Daily> daily = new ArrayList<>(span);
    for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
      daily.add(new Daily(d, signups.getOrDefault(d, 0L), posts.getOrDefault(d, 0L)));
    }
    return new Stats(users == null ? 0 : users, blogs == null ? 0 : blogs, daily);
  }

  private Map<LocalDate, Long> perDay(String sql, LocalDate from) {
    Map<LocalDate, Long> map = new HashMap<>();
    jdbc.query(
        sql,
        (RowCallbackHandler) rs -> map.put(rs.getDate("d").toLocalDate(), rs.getLong("c")),
        Date.valueOf(from));
    return map;
  }
}
