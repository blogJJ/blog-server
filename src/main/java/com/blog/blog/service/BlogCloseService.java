package com.blog.blog.service;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogCloseNotice;
import com.blog.blog.domain.BlogOwnerTransfer;
import com.blog.blog.domain.CloseNoticeStage;
import com.blog.blog.domain.CloseReason;
import com.blog.blog.repository.BlogCloseNoticeRepository;
import com.blog.blog.repository.BlogOwnerTransferRepository;
import com.blog.blog.repository.BlogRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 블로그 폐쇄·철회와 폐쇄 알림 (T088, T089 ①②, BLG-09, D-12~D-14, D-19, D-67, D-96).
 *
 * <ul>
 *   <li>폐쇄 예정 시각은 누른 날짜 + 7일 04:00 (Asia/Seoul, 서버 시간대). 10월 2일 15:00에 누르면 10월 9일 04:00.
 *   <li>폐쇄 예정 7일 동안에도 글쓰기·댓글은 평소처럼 된다(D-67). 위임만 막힌다(D-20). 대기 중인 위임 요청은 바로 취소한다.
 *   <li>알림은 블로그장을 뺀 멤버와 구독자에게 즉시·3일 전·1일 전, 철회하면 철회 알림 (D-13, D-14). 끌 수 없는 알림이다.
 *   <li>보낸 알림은 blog_close_notices에 (블로그, 예정 시각, 단계)로 남겨 배치를 두 번 돌리거나 밀린 날을 몰아 처리해도 한 번만 보낸다
 *       (SC-005).
 *   <li>블로그장 폐쇄(OWNER), 메인 관리자 강제 폐쇄(ADMIN, D-96), 블로그장 박탈 + 부블로그장 없음(OWNER_DEMOTED, ADM-07)이 모두
 *       {@link #scheduleClose}를 쓴다. 누가 부를 수 있는지는 부르는 쪽(컨트롤러·관리자 서비스)이 확인한다.
 *   <li>철회는 사유와 관계없이 그 블로그의 블로그장이 7일 안에 한다 (D-19, D-96). 메인 관리자도 할 수 있다 (D-122).
 * </ul>
 */
@Service
public class BlogCloseService {

  /** 폐쇄는 누른 날짜 + 7일의 이 시각 (D-12) */
  public static final LocalTime CLOSE_TIME = LocalTime.of(4, 0);

  public static final int CLOSE_AFTER_DAYS = 7;

  private static final Logger log = LoggerFactory.getLogger(BlogCloseService.class);
  private static final DateTimeFormatter AT_FORMAT =
      DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");

  private final BlogRepository blogRepository;
  private final BlogOwnerTransferRepository transferRepository;
  private final BlogCloseNoticeRepository noticeRepository;
  private final NotificationService notificationService;
  private final Clock clock;

  /** 배치는 블로그마다 따로 커밋한다. 한 블로그가 실패해도 다른 블로그는 처리된다. */
  private final TransactionTemplate perBlog;

  public BlogCloseService(
      BlogRepository blogRepository,
      BlogOwnerTransferRepository transferRepository,
      BlogCloseNoticeRepository noticeRepository,
      NotificationService notificationService,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.blogRepository = blogRepository;
    this.transferRepository = transferRepository;
    this.noticeRepository = noticeRepository;
    this.notificationService = notificationService;
    this.clock = clock;
    this.perBlog = new TransactionTemplate(transactionManager);
    this.perBlog.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** 누른 시각으로 폐쇄 예정 시각을 정한다: 누른 날짜 + 7일 04:00 (D-12). */
  public static LocalDateTime scheduledAt(LocalDateTime pressedAt) {
    return pressedAt.toLocalDate().plusDays(CLOSE_AFTER_DAYS).atTime(CLOSE_TIME);
  }

  /**
   * 폐쇄 예정으로 바꾼다. 권한은 확인하지 않는다(블로그장 폐쇄는 컨트롤러가, 관리자 폐쇄는 관리자 기능이 확인한다).
   *
   * @param reason OWNER(블로그장), ADMIN(관리자 강제 폐쇄), OWNER_DEMOTED(블로그장 박탈 + 부블로그장 없음)
   * @param actorId 누른 회원(알림의 actor). 시스템이면 null
   * @throws BusinessException 블로그가 없으면 NOT_FOUND, 이미 폐쇄 예정이거나 폐쇄됐으면 CONFLICT
   */
  @Transactional
  public Blog scheduleClose(Long blogId, CloseReason reason, Long actorId) {
    Blog blog = lock(blogId);
    if (!blog.isActive()) {
      throw new BusinessException(
          ErrorCode.CONFLICT, blog.isClosing() ? "이미 폐쇄 예정인 블로그예요." : "이미 폐쇄된 블로그예요.");
    }
    LocalDateTime now = now();
    LocalDateTime at = scheduledAt(now);
    blog.scheduleClose(at, reason);
    for (BlogOwnerTransfer t : transferRepository.findPendingByBlogId(blogId)) {
      t.cancel(now);
      notificationService.notify(
          t.getToUser().getId(),
          null,
          NotificationType.OWNER_TRANSFER_RESULT,
          NotificationTargetType.BLOG,
          blogId,
          "'" + blog.getName() + "' 블로그가 폐쇄 예정이 되어 위임 요청이 취소되었어요.");
    }
    send(blog, at, CloseNoticeStage.IMMEDIATE, actorId, immediateMessage(blog, at));
    return blog;
  }

  /**
   * 폐쇄 철회 (D-19). 그 블로그의 블로그장(blogs.owner_id)만, 폐쇄되기 전에만 한다. 같은 대상에게 철회 알림을 보낸다.
   *
   * @throws BusinessException 블로그장이 아니면 FORBIDDEN, 폐쇄 예정이 아니면 CONFLICT
   */
  @Transactional
  public Blog revokeClose(Long blogId, Long actorId) {
    return revokeClose(blogId, actorId, false);
  }

  /**
   * 폐쇄 철회. 메인 관리자도 사유와 관계없이 철회한다 (D-122). 관리자인지는 부르는 쪽이 확인한다.
   *
   * @param byAdmin 메인 관리자가 철회하면 true
   */
  @Transactional
  public Blog revokeClose(Long blogId, Long actorId, boolean byAdmin) {
    Blog blog = lock(blogId);
    if (!byAdmin && !blog.getOwner().getId().equals(actorId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "폐쇄 철회는 블로그장만 할 수 있어요.");
    }
    if (!blog.isClosing()) {
      throw new BusinessException(ErrorCode.CONFLICT, "폐쇄 예정인 블로그가 아니에요.");
    }
    LocalDateTime at = blog.getCloseScheduledAt();
    blog.revokeClose();
    send(
        blog,
        at,
        CloseNoticeStage.REVOKED,
        actorId,
        "'" + blog.getName() + "' 블로그 폐쇄가 철회되었어요. 평소처럼 운영돼요.");
    return blog;
  }

  /**
   * 04:00 배치 ① 폐쇄 예정 시각이 지난 블로그를 폐쇄한다. 서버가 꺼졌던 날이 있어도 다음 배치가 밀린 것을 모두 처리한다.
   *
   * @return 폐쇄한 블로그 수
   */
  public int closeDue(LocalDateTime now) {
    int closed = 0;
    for (Long blogId : blogRepository.findDueToClose(now)) {
      try {
        if (Boolean.TRUE.equals(perBlog.execute(tx -> closeOne(blogId, now)))) {
          closed++;
        }
      } catch (RuntimeException e) {
        log.error("batch close failed blogId={}", blogId, e);
      }
    }
    return closed;
  }

  /**
   * 04:00 배치 ② 폐쇄까지 3일·1일 남은 블로그에 알림. 날짜로 세므로 10월 9일 04:00 폐쇄면 10월 6일에 3일 전, 10월 8일에 1일 전. 서버가 꺼져
   * 3일 전 배치를 놓쳤으면 다음 배치가 남은 날짜로 보내고, 1일 전이 되면 1일 전 알림만 보낸다.
   *
   * @return 보낸 단계 수 (블로그 × 단계)
   */
  public int sendReminders(LocalDateTime now) {
    int sent = 0;
    for (Long blogId : blogRepository.findClosingAfter(now)) {
      try {
        if (Boolean.TRUE.equals(perBlog.execute(tx -> remindOne(blogId, now)))) {
          sent++;
        }
      } catch (RuntimeException e) {
        log.error("batch close reminder failed blogId={}", blogId, e);
      }
    }
    return sent;
  }

  private boolean closeOne(Long blogId, LocalDateTime now) {
    Blog blog = lock(blogId);
    if (!blog.isClosing() || blog.getCloseScheduledAt().isAfter(now)) {
      return false; // 그 사이 철회됨
    }
    blog.markClosed(now);
    log.info("blog closed blogId={} reason={}", blogId, blog.getCloseReason());
    return true;
  }

  private boolean remindOne(Long blogId, LocalDateTime now) {
    Blog blog = lock(blogId);
    LocalDateTime at = blog.getCloseScheduledAt();
    if (!blog.isClosing() || !at.isAfter(now)) {
      return false;
    }
    long daysLeft = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate());
    CloseNoticeStage stage;
    String when;
    if (daysLeft <= 1) {
      stage = CloseNoticeStage.D1;
      when = "내일";
    } else if (daysLeft <= 3) {
      stage = CloseNoticeStage.D3;
      when = daysLeft + "일 뒤";
    } else {
      return false;
    }
    if (noticeRepository.existsByBlogIdAndCloseScheduledAtAndStage(blogId, at, stage)) {
      return false;
    }
    send(
        blog,
        at,
        stage,
        null,
        "'"
            + blog.getName()
            + "' 블로그가 "
            + when
            + "("
            + at.format(AT_FORMAT)
            + ") 폐쇄돼요. 필요한 글은 미리 옮겨 두세요.");
    return true;
  }

  /** 발송 기록을 남기고 알림을 보낸다. 즉시·철회는 블로그장이 누를 때마다 보내고, 기록은 처음 한 번만 남긴다. */
  private void send(
      Blog blog, LocalDateTime at, CloseNoticeStage stage, Long actorId, String message) {
    if (!noticeRepository.existsByBlogIdAndCloseScheduledAtAndStage(blog.getId(), at, stage)) {
      noticeRepository.save(new BlogCloseNotice(blog, at, stage));
    }
    NotificationType type =
        stage == CloseNoticeStage.REVOKED
            ? NotificationType.BLOG_CLOSE_CANCELED
            : NotificationType.BLOG_CLOSING;
    notificationService.notifyAll(
        noticeRepository.findReceiverIds(blog.getId()),
        actorId,
        type,
        NotificationTargetType.BLOG,
        blog.getId(),
        message);
  }

  private static String immediateMessage(Blog blog, LocalDateTime at) {
    String why =
        switch (blog.getCloseReason()) {
          case ADMIN -> " 운영 정책에 따라 관리자가 폐쇄했어요.";
          case OWNER_DEMOTED -> " 블로그장 권한 박탈로 인한 폐쇄 조치예요.";
          default -> "";
        };
    return "'"
        + blog.getName()
        + "' 블로그가 "
        + at.format(AT_FORMAT)
        + "에 폐쇄될 예정이에요."
        + why
        + " 필요한 글은 미리 옮겨 두세요.";
  }

  private Blog lock(Long blogId) {
    return blogRepository
        .findByIdForUpdate(blogId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
