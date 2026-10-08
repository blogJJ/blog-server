package com.blog.board.service;

import com.blog.board.repository.PostRepository;
import com.blog.common.crypto.HashUtil;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회수 (T065, BRD-11, D-77, SC-007). 같은 사람이 같은 날 같은 글을 보면 한 번만 센다.
 *
 * <p>조회 기록(post_views)의 UNIQUE(post_id, viewer_key, view_date)에 INSERT IGNORE로 넣고, 새로 들어갔을 때만
 * view_count를 1 올린다. 동시에 여러 번 열어도 한 번만 오른다. 회원은 회원 번호, 비회원은 IP와 브라우저 정보를 섞은 해시로 사람을 나눈다.
 */
@Service
public class ViewCountService {

  private final JdbcTemplate jdbc;
  private final PostRepository postRepository;
  private final HashUtil hashUtil;
  private final Clock clock;

  public ViewCountService(
      JdbcTemplate jdbc, PostRepository postRepository, HashUtil hashUtil, Clock clock) {
    this.jdbc = jdbc;
    this.postRepository = postRepository;
    this.hashUtil = hashUtil;
    this.clock = clock;
  }

  /** 보는 사람을 나누는 값. 64자 이하. */
  public String viewerKey(Long userId, String ip, String userAgent) {
    if (userId != null) {
      return "u:" + userId;
    }
    return "g:"
        + hashUtil
            .hash((ip == null ? "" : ip) + "|" + (userAgent == null ? "" : userAgent))
            .substring(0, 40);
  }

  /**
   * 조회를 기록한다. 글 읽기가 실패하지 않게 따로 커밋한다.
   *
   * @return 오늘 처음 본 것이라 조회수가 올랐으면 true
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean record(Long postId, String viewerKey) {
    int inserted =
        jdbc.update(
            "insert ignore into post_views (post_id, viewer_key, view_date) values (?, ?, ?)",
            postId,
            viewerKey,
            LocalDate.now(clock));
    if (inserted == 1) {
      postRepository.increaseViewCount(postId);
      return true;
    }
    return false;
  }
}
