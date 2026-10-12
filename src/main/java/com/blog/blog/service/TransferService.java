package com.blog.blog.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogManagerPermission;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.BlogOwnerTransfer;
import com.blog.blog.domain.ManagerPermission;
import com.blog.blog.repository.BlogManagerPermissionRepository;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.blog.repository.BlogOwnerTransferRepository;
import com.blog.blog.repository.BlogRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 블로그장 위임 (T087, T089 ③, BLG-08, D-16, D-20, D-31).
 *
 * <ul>
 *   <li>요청은 블로그장만(컨트롤러). 받는 사람은 그 블로그의 멤버·부블로그장이고, 그 블로그에서 정지 중이거나 계정 정지 중이면 안 된다.
 *   <li>폐쇄 예정 중에는 요청할 수 없다. 먼저 철회해야 한다 (D-20). 블로그마다 대기 중인 요청은 하나.
 *   <li>받는 멤버가 수락해야 넘어간다 (D-16). 수락하면 블로그장과 받는 멤버의 역할을 바꾸고 blogs.owner_id를 바꾼다. 받는 사람이 부블로그장이었으면 이전
 *       블로그장이 그 부블로그장 자리와 권한을 그대로 받는다.
 *   <li>받으면 블로그 개수 제한(BLG-10)을 넘는 회원은 수락할 수 없다. 받는 사람 행을 잠그고 센다 (D-68).
 *   <li>7일 안에 응답이 없으면 04:00 배치가 EXPIRED로 바꾼다 (D-31). 수락·거절·자동 취소는 블로그장에게 알린다.
 *   <li>블로그장은 대기 중인 요청을 취소할 수 있고, 받는 멤버에게 알린다 (D-124).
 * </ul>
 */
@Service
public class TransferService {

  private static final Logger log = LoggerFactory.getLogger(TransferService.class);
  private static final DateTimeFormatter AT_FORMAT =
      DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");

  /** 위임 요청 한 건 */
  public record TransferRow(
      Long id,
      Long blogId,
      String blogName,
      String blogSlug,
      String fromNickname,
      Long toUserId,
      String toNickname,
      LocalDateTime expiresAt,
      LocalDateTime createdAt) {}

  private final BlogRepository blogRepository;
  private final BlogMemberRepository memberRepository;
  private final BlogManagerPermissionRepository permissionRepository;
  private final BlogOwnerTransferRepository transferRepository;
  private final UserRepository userRepository;
  private final BlogService blogService;
  private final NotificationService notificationService;
  private final Clock clock;
  private final TransactionTemplate perTransfer;

  public TransferService(
      BlogRepository blogRepository,
      BlogMemberRepository memberRepository,
      BlogManagerPermissionRepository permissionRepository,
      BlogOwnerTransferRepository transferRepository,
      UserRepository userRepository,
      BlogService blogService,
      NotificationService notificationService,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.blogRepository = blogRepository;
    this.memberRepository = memberRepository;
    this.permissionRepository = permissionRepository;
    this.transferRepository = transferRepository;
    this.userRepository = userRepository;
    this.blogService = blogService;
    this.notificationService = notificationService;
    this.clock = clock;
    this.perTransfer = new TransactionTemplate(transactionManager);
    this.perTransfer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** 위임 요청. 블로그장인지는 컨트롤러가 확인한다. */
  @Transactional
  public BlogOwnerTransfer request(Long blogId, Long ownerId, Long toUserId) {
    Blog blog = lockBlog(blogId);
    if (blog.isClosing()) {
      throw new BusinessException(ErrorCode.CONFLICT, "폐쇄 예정 중에는 위임할 수 없어요. 먼저 폐쇄를 철회해 주세요.");
    }
    if (!blog.isActive()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    if (toUserId == null || toUserId.equals(ownerId)) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "위임할 멤버를 골라 주세요.");
    }
    BlogMember target = receiverMembership(blogId, toUserId);
    List<BlogOwnerTransfer> pending = transferRepository.findPendingByBlogId(blogId);
    if (!pending.isEmpty()) {
      BlogOwnerTransfer p = pending.get(0);
      throw new BusinessException(
          ErrorCode.CONFLICT,
          "이미 "
              + nickname(p.getToUser())
              + "님에게 보낸 위임 요청이 있어요 ("
              + p.getExpiresAt().format(AT_FORMAT)
              + "까지).");
    }
    LocalDateTime now = now();
    BlogOwnerTransfer transfer =
        transferRepository.save(
            new BlogOwnerTransfer(
                blog, userRepository.getReferenceById(ownerId), target.getUser(), now));
    notificationService.notify(
        toUserId,
        ownerId,
        NotificationType.OWNER_TRANSFER_REQUEST,
        NotificationTargetType.BLOG,
        blogId,
        "'"
            + blog.getName()
            + "' 블로그장 위임 요청을 받았어요. "
            + transfer.getExpiresAt().format(AT_FORMAT)
            + "까지 수락하거나 거절해 주세요.");
    return transfer;
  }

  /** 받는 멤버가 수락한다. 역할을 바꾸고 블로그장에게 알린다. */
  @Transactional
  public void accept(Long transferId, Long userId) {
    BlogOwnerTransfer transfer = pendingFor(transferId, userId);
    Blog blog = transfer.getBlog();
    LocalDateTime now = now();
    if (!blog.isActive()) {
      throw new BusinessException(ErrorCode.CONFLICT, "운영 중인 블로그만 위임받을 수 있어요.");
    }
    User from = transfer.getFromUser();
    if (!blog.getOwner().getId().equals(from.getId())) {
      throw new BusinessException(ErrorCode.CONFLICT, "블로그장이 바뀌어 이 요청은 더 이상 쓸 수 없어요.");
    }
    BlogMember receiver = receiverMembership(blog.getId(), userId);
    BlogMember owner =
        memberRepository
            .findByBlogIdAndUserId(blog.getId(), from.getId())
            .filter(BlogMember::isOwner)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.CONFLICT, "블로그장이 바뀌어 이 요청은 더 이상 쓸 수 없어요."));

    userRepository.findByIdForUpdate(userId);
    try {
      blogService.checkLimit(userId, blog.getVisibility(), null);
    } catch (BusinessException e) {
      throw new BusinessException(
          ErrorCode.CONFLICT, "받으면 블로그 개수 제한을 넘어요. " + e.getMessage() + " 다른 블로그를 정리한 뒤 수락해 주세요.");
    }

    boolean wasManager = receiver.isManager();
    List<ManagerPermission> permissions =
        permissionRepository.findByBlogMemberId(receiver.getId()).stream()
            .map(BlogManagerPermission::getPermission)
            .toList();
    permissionRepository.deleteByBlogMemberId(receiver.getId());
    permissionRepository.flush();
    receiver.promoteToOwner();
    owner.demoteFromOwner();
    if (wasManager) {
      owner.promoteToManager(now);
      for (ManagerPermission p : permissions) {
        permissionRepository.save(new BlogManagerPermission(owner, p));
      }
    }
    blog.changeOwner(receiver.getUser());
    transfer.accept(now);
    log.info("blog owner transferred blogId={} from={} to={}", blog.getId(), from.getId(), userId);
    notifyOwner(
        transfer,
        userId,
        nickname(receiver.getUser())
            + "님이 '"
            + blog.getName()
            + "' 블로그장 위임을 수락했어요. 이제 "
            + (wasManager ? "부블로그장" : "멤버")
            + "이에요.");
  }

  /** 받는 멤버가 거절한다. 블로그장은 그대로이고 블로그장에게 알린다. */
  @Transactional
  public void reject(Long transferId, Long userId) {
    BlogOwnerTransfer transfer = pendingFor(transferId, userId);
    transfer.reject(now());
    notifyOwner(
        transfer,
        userId,
        nickname(transfer.getToUser())
            + "님이 '"
            + transfer.getBlog().getName()
            + "' 블로그장 위임을 거절했어요.");
  }

  /** 블로그장이 대기 중인 요청을 취소한다 (D-124). 블로그장인지는 컨트롤러가 확인한다. */
  @Transactional
  public void cancelByOwner(Long blogId, Long ownerId) {
    lockBlog(blogId);
    BlogOwnerTransfer transfer =
        transferRepository.findPendingByBlogId(blogId).stream()
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "취소할 위임 요청이 없어요."));
    transfer.cancel(now());
    notificationService.notify(
        transfer.getToUser().getId(),
        ownerId,
        NotificationType.OWNER_TRANSFER_RESULT,
        NotificationTargetType.BLOG,
        blogId,
        "'" + transfer.getBlog().getName() + "' 블로그장 위임 요청이 취소되었어요.");
  }

  /**
   * 받는 멤버가 블로그를 떠나거나 탈퇴하면 그 사람에게 온 대기 요청을 취소하고 블로그장에게 알린다.
   *
   * @param blogId 이 블로그의 요청만. null이면 모든 블로그 (회원 탈퇴)
   */
  @Transactional
  public void cancelForLeavingMember(Long blogId, Long userId) {
    LocalDateTime now = now();
    for (BlogOwnerTransfer t : transferRepository.findPendingInvolving(userId)) {
      if (!t.getToUser().getId().equals(userId)
          || (blogId != null && !t.getBlog().getId().equals(blogId))) {
        continue;
      }
      t.cancel(now);
      notifyOwner(t, null, "받는 멤버가 '" + t.getBlog().getName() + "' 블로그를 떠나 블로그장 위임 요청이 취소되었어요.");
    }
  }

  /**
   * 04:00 배치 ③ 7일 지난 대기 요청을 EXPIRED로 바꾸고 블로그장에게 알린다. 요청마다 따로 커밋한다.
   *
   * @return 바꾼 요청 수
   */
  public int expireDue(LocalDateTime now) {
    List<Long> ids =
        transferRepository.findExpired(now).stream().map(BlogOwnerTransfer::getId).toList();
    int expired = 0;
    for (Long id : ids) {
      try {
        Boolean done =
            perTransfer.execute(
                tx -> {
                  BlogOwnerTransfer t = transferRepository.findByIdForUpdate(id).orElse(null);
                  if (t == null || !t.isPending() || !t.isExpiredAt(now)) {
                    return false;
                  }
                  t.expire(now);
                  notifyOwner(
                      t,
                      null,
                      "'" + t.getBlog().getName() + "' 블로그장 위임 요청이 7일 동안 응답이 없어 자동으로 취소되었어요.");
                  return true;
                });
        if (Boolean.TRUE.equals(done)) {
          expired++;
        }
      } catch (RuntimeException e) {
        log.error("batch transfer expire failed transferId={}", id, e);
      }
    }
    return expired;
  }

  /** 관리 화면: 이 블로그의 대기 중인 요청 */
  @Transactional(readOnly = true)
  public Optional<TransferRow> pendingOfBlog(Long blogId) {
    return transferRepository.findPendingByBlogId(blogId).stream().findFirst().map(this::row);
  }

  /** 위임 수락 화면: 내가 받은 대기 중인 요청 (7일 지난 것은 뺀다) */
  @Transactional(readOnly = true)
  public List<TransferRow> pendingForMe(Long userId) {
    LocalDateTime now = now();
    return transferRepository.findPendingForReceiver(userId).stream()
        .filter(t -> !t.isExpiredAt(now))
        .map(this::row)
        .toList();
  }

  private TransferRow row(BlogOwnerTransfer t) {
    Blog b = t.getBlog();
    return new TransferRow(
        t.getId(),
        b.getId(),
        b.getName(),
        b.getSlug(),
        nickname(t.getFromUser()),
        t.getToUser().getId(),
        nickname(t.getToUser()),
        t.getExpiresAt(),
        t.getCreatedAt());
  }

  /** 받는 사람이 될 수 있는 멤버인지: 그 블로그의 멤버·부블로그장이고, 블로그 정지·계정 정지 중이 아니다. */
  private BlogMember receiverMembership(Long blogId, Long userId) {
    LocalDateTime now = now();
    BlogMember member =
        memberRepository
            .findByBlogIdAndUserId(blogId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이 블로그의 멤버가 아니에요."));
    User user = member.getUser();
    if (member.isOwner()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 블로그장이에요.");
    }
    if (!user.isActive()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "탈퇴한 회원이에요.");
    }
    if (member.isSuspendedAt(now)) {
      throw new BusinessException(ErrorCode.CONFLICT, "이 블로그에서 정지 중인 멤버는 블로그장이 될 수 없어요.");
    }
    if (user.isSuspendedAt(now)) {
      throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED, "계정 정지 중에는 블로그장이 될 수 없어요.");
    }
    return member;
  }

  /** 내가 받은 대기 중인 요청을 잠근다. 폐쇄와 같은 순서(블로그 → 요청)로 잠가 서로 기다리다 멈추지 않게 한다. */
  private BlogOwnerTransfer pendingFor(Long transferId, Long userId) {
    Long blogId =
        transferRepository
            .findBlogIdById(transferId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    lockBlog(blogId);
    BlogOwnerTransfer transfer =
        transferRepository
            .findByIdForUpdate(transferId)
            .filter(t -> t.getToUser().getId().equals(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (!transfer.isPending()) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 처리된 위임 요청이에요.");
    }
    if (transfer.isExpiredAt(now())) {
      throw new BusinessException(ErrorCode.CONFLICT, "7일이 지나 취소된 위임 요청이에요.");
    }
    return transfer;
  }

  private void notifyOwner(BlogOwnerTransfer t, Long actorId, String message) {
    notificationService.notify(
        t.getFromUser().getId(),
        actorId,
        NotificationType.OWNER_TRANSFER_RESULT,
        NotificationTargetType.BLOG,
        t.getBlog().getId(),
        message);
  }

  private Blog lockBlog(Long blogId) {
    return blogRepository
        .findByIdForUpdate(blogId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static String nickname(User user) {
    return user.isActive() && user.getNickname() != null ? user.getNickname() : "탈퇴한 회원";
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
