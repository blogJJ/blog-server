package com.blog.batch;

import com.blog.board.service.ImageService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 04:00 배치의 정리 작업 (T090, 4.5, D-40, D-60, D-83, D-86, data-model.md 04:00 배치 ④~⑦).
 *
 * <ol start="4">
 *   <li>삭제한 지 30일 지난 글·댓글·사진을 완전히 지운다. 폐쇄된 지 30일 지난 블로그는 글·사진·카테고리·블랙리스트를 지우고 주소(slug)를 비운다. 블로그 행은
 *       1년 보관하는 제재·신고 기록이 가리키므로 남긴다 (D-86).
 *   <li>탈퇴한 지 30일 지난 회원의 이름·닉네임·전화번호·프로필 사진·소개를 비운다.
 *   <li>보관 기간(회원마다 30일 또는 7일)이 지난 알림, 만료된 인증번호·이메일 찾기 토큰·Refresh Token을 지운다 (D-83).
 *   <li>1년 지난 운영 기록(관리자 활동, 신고, 경고·정지)을 지운다 (D-60). 남은 제재가 가리키는 신고는 그 제재가 지워질 때까지 둔다.
 * </ol>
 *
 * <p>여러 테이블을 한 번에 지우므로 엔티티 대신 SQL로 처리한다. 단계마다(폐쇄 블로그는 블로그마다) 따로 커밋해 하나가 실패해도 나머지는 진행하고, 디스크 파일은 커밋한
 * 뒤에 지운다.
 */
@Component
public class RetentionCleanup {

  /** 삭제·탈퇴·폐쇄 데이터 보관 기간 (D-40) */
  public static final int KEEP_DAYS = 30;

  /** 운영 기록 보관 기간 (D-60) */
  public static final int LOG_KEEP_YEARS = 1;

  private static final int CHUNK = 500;
  private static final Logger log = LoggerFactory.getLogger(RetentionCleanup.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ImageService imageService;

  public RetentionCleanup(
      NamedParameterJdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      ImageService imageService) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.imageService = imageService;
  }

  /** ④~⑦을 차례로 한다. 한 단계가 실패해도 다음 단계는 한다. */
  public void run(LocalDateTime now) {
    LocalDateTime cutoff = now.minusDays(KEEP_DAYS);
    step("deleted posts", () -> purgeDeletedPosts(cutoff));
    step("deleted comments", () -> purgeDeletedComments(cutoff));
    step("deleted images", () -> purgeDeletedImages(cutoff));
    step("closed blogs", () -> purgeClosedBlogs(cutoff));
    step("withdrawn users", () -> anonymizeWithdrawnUsers(cutoff));
    step("expired notifications and tokens", () -> purgeExpired(now));
    step("old operation logs", () -> purgeOldLogs(now.minusYears(LOG_KEEP_YEARS)));
  }

  /** ④ 삭제한 지 30일 지난 글과 그 글의 댓글·사진·태그·좋아요·조회 기록 */
  int purgeDeletedPosts(LocalDateTime cutoff) {
    List<Long> ids =
        jdbc.queryForList(
            "select id from posts where status = 'DELETED' and deleted_at < :cutoff",
            cutoffParam(cutoff),
            Long.class);
    int count = 0;
    for (List<Long> chunk : chunks(ids)) {
      List<String> files = inTx(() -> purgePosts(chunk));
      files.forEach(imageService::deleteQuietly);
      count += chunk.size();
    }
    return count;
  }

  /** ④ 삭제한 지 30일 지난 댓글. 보이는 답글이 남은 댓글은 "삭제된 댓글입니다" 자리로 남긴다. */
  int purgeDeletedComments(LocalDateTime cutoff) {
    return inTx(
        () -> {
          Map<String, Object> p = cutoffParam(cutoff);
          int replies =
              jdbc.update(
                  "delete from comments where status = 'DELETED' and deleted_at < :cutoff"
                      + " and parent_id is not null",
                  p);
          int roots =
              jdbc.update(
                  "delete c from comments c left join comments r on r.parent_id = c.id"
                      + " where c.status = 'DELETED' and c.deleted_at < :cutoff"
                      + " and c.parent_id is null and r.id is null",
                  p);
          return replies + roots;
        });
  }

  /** ④ 본문에서 뺀 지 30일 지난 사진(글은 남아 있고 사진만 뺀 경우)과, 올린 뒤 30일 동안 어느 글에도 붙지 않은 사진(글을 저장하지 않고 나간 경우) */
  int purgeDeletedImages(LocalDateTime cutoff) {
    Map<String, Object> p = cutoffParam(cutoff);
    List<String> files =
        inTx(
            () -> {
              List<String> names =
                  jdbc.queryForList(
                      "select stored_name from post_images where deleted_at < :cutoff"
                          + " or (post_id is null and deleted_at is null and created_at < :cutoff)",
                      p,
                      String.class);
              jdbc.update(
                  "delete from post_images where deleted_at < :cutoff"
                      + " or (post_id is null and deleted_at is null and created_at < :cutoff)",
                  p);
              return names;
            });
    files.forEach(imageService::deleteQuietly);
    return files.size();
  }

  /** ④ 폐쇄된 지 30일 지난 블로그의 내용. 주소가 아직 있는 블로그만 하므로 한 번만 처리된다 (D-86). */
  int purgeClosedBlogs(LocalDateTime cutoff) {
    List<Long> blogIds =
        jdbc.queryForList(
            "select id from blogs where status = 'CLOSED' and closed_at < :cutoff"
                + " and slug is not null order by id",
            cutoffParam(cutoff),
            Long.class);
    int count = 0;
    for (Long blogId : blogIds) {
      try {
        List<String> files = inTx(() -> purgeBlog(blogId));
        files.forEach(imageService::deleteQuietly);
        count++;
      } catch (RuntimeException e) {
        log.error("retention purge failed blogId={}", blogId, e);
      }
    }
    return count;
  }

  /** ⑤ 탈퇴한 지 30일 지난 회원의 개인정보. 행과 글은 남기고 글은 "탈퇴한 회원"으로 보인다. */
  int anonymizeWithdrawnUsers(LocalDateTime cutoff) {
    Map<String, Object> p = cutoffParam(cutoff);
    String target =
        " from users where status = 'WITHDRAWN' and withdrawn_at < :cutoff"
            + " and (name is not null or nickname is not null or phone is not null"
            + " or profile_image is not null or bio is not null)";
    List<String> files = new ArrayList<>();
    int count =
        inTx(
            () -> {
              files.addAll(
                  jdbc.queryForList(
                      "select profile_image" + target + " and profile_image is not null",
                      p,
                      String.class));
              return jdbc.update(
                  "update users set name = null, nickname = null, phone = null,"
                      + " profile_image = null, bio = null"
                      + " where status = 'WITHDRAWN' and withdrawn_at < :cutoff"
                      + " and (name is not null or nickname is not null or phone is not null"
                      + " or profile_image is not null or bio is not null)",
                  p);
            });
    files.forEach(imageService::deleteQuietly);
    return count;
  }

  /** ⑥ 보관 기간 지난 알림, 만료된 인증번호·임시 토큰·Refresh Token */
  int purgeExpired(LocalDateTime now) {
    return inTx(
        () -> {
          Map<String, Object> p = Map.of("now", now);
          return jdbc.update(
                  "delete n from notifications n join users u on u.id = n.receiver_id"
                      + " where n.created_at < date_sub(:now, interval u.notification_keep_days day)",
                  p)
              + jdbc.update("delete from verification_codes where expires_at < :now", p)
              + jdbc.update("delete from account_find_tokens where expires_at < :now", p)
              + jdbc.update("delete from refresh_tokens where expires_at < :now", p);
        });
  }

  /** ⑦ 1년 지난 운영 기록. 제재를 먼저 지우고, 남은 제재가 가리키지 않는 신고만 지운다. */
  int purgeOldLogs(LocalDateTime cutoff) {
    return inTx(
        () -> {
          Map<String, Object> p = cutoffParam(cutoff);
          return jdbc.update("delete from member_sanctions where created_at < :cutoff", p)
              + jdbc.update("delete from owner_sanctions where created_at < :cutoff", p)
              + jdbc.update("delete from user_sanctions where created_at < :cutoff", p)
              + jdbc.update("delete from admin_action_logs where created_at < :cutoff", p)
              + jdbc.update(
                  "delete from reports where created_at < :cutoff"
                      + " and not exists (select 1 from member_sanctions s where s.report_id = reports.id)"
                      + " and not exists (select 1 from owner_sanctions s where s.report_id = reports.id)"
                      + " and not exists (select 1 from user_sanctions s where s.report_id = reports.id)",
                  p);
        });
  }

  /** 블로그 하나의 글·사진·카테고리·블랙리스트를 지우고 주소를 비운다. 지울 파일 이름을 돌려준다. */
  private List<String> purgeBlog(Long blogId) {
    Map<String, Object> p = Map.of("blogId", blogId);
    List<String> files = new ArrayList<>();
    List<Long> postIds =
        jdbc.queryForList("select id from posts where blog_id = :blogId", p, Long.class);
    for (List<Long> chunk : chunks(postIds)) {
      files.addAll(purgePosts(chunk));
    }
    files.addAll(
        jdbc.queryForList(
            "select cover_image from blogs where id = :blogId and cover_image is not null",
            p,
            String.class));
    jdbc.update(
        "delete from blacklist_inquiries where blacklist_id in"
            + " (select id from blog_blacklist where blog_id = :blogId)",
        p);
    jdbc.update("delete from blog_blacklist where blog_id = :blogId", p);
    jdbc.update("delete from categories where blog_id = :blogId", p);
    jdbc.update(
        "update blogs set slug = null, cover_image = null, share_key = null where id = :blogId", p);
    log.info("closed blog content purged blogId={} posts={}", blogId, postIds.size());
    return files;
  }

  /** 글과 그 글에 딸린 행을 FK 순서대로 지운다. 지울 사진 파일 이름을 돌려준다. */
  private List<String> purgePosts(List<Long> postIds) {
    if (postIds.isEmpty()) {
      return List.of();
    }
    Map<String, Object> p = Map.of("ids", postIds);
    List<String> files =
        jdbc.queryForList(
            "select stored_name from post_images where post_id in (:ids)", p, String.class);
    jdbc.update("delete from post_tags where post_id in (:ids)", p);
    jdbc.update("delete from post_images where post_id in (:ids)", p);
    jdbc.update("delete from post_likes where post_id in (:ids)", p);
    jdbc.update("delete from post_views where post_id in (:ids)", p);
    jdbc.update("update comments set parent_id = null where post_id in (:ids)", p);
    jdbc.update("delete from comments where post_id in (:ids)", p);
    jdbc.update("delete from posts where id in (:ids)", p);
    return files;
  }

  private void step(String name, IntSupplier work) {
    try {
      int n = work.getAsInt();
      if (n > 0) {
        log.info("retention {}: {}", name, n);
      }
    } catch (RuntimeException e) {
      log.error("retention step failed: {}", name, e);
    }
  }

  /** SQL의 :cutoff 자리에 넣을 값 */
  private static Map<String, Object> cutoffParam(LocalDateTime cutoff) {
    return Map.of("cutoff", cutoff);
  }

  private <T> T inTx(Supplier<T> work) {
    return tx.execute(status -> work.get());
  }

  private static List<List<Long>> chunks(List<Long> ids) {
    List<List<Long>> chunks = new ArrayList<>();
    for (int i = 0; i < ids.size(); i += CHUNK) {
      chunks.add(ids.subList(i, Math.min(ids.size(), i + CHUNK)));
    }
    return chunks;
  }
}
