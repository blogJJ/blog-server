package com.blog.blog.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.BlacklistInquiry;
import com.blog.blog.domain.BlogBlacklist;
import com.blog.blog.domain.InquiryStatus;
import com.blog.blog.repository.BlacklistInquiryRepository;
import com.blog.blog.repository.BlogBlacklistRepository;
import com.blog.common.crypto.HashUtil;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블랙리스트 확인과 해제 문의 (T078, T079, BLG-11, BLG-12, D-34, D-55).
 *
 * <ul>
 *   <li>참여 신청 때 이메일 또는 전화번호 해시가 해제되지 않은 기록과 같으면 막는다. 재가입해도 같은 값이면 걸린다.
 *   <li>걸린 사람이 문의를 남기면 서버가 이름·전화번호가 강퇴된 사람과 같은지 해시로 비교해 저장한다. 관리 화면에는 원문 없이 일치 여부만 보인다.
 *   <li>해제·거절은 블로그장 또는 멤버 관리 권한 부블로그장(컨트롤러에서 확인). 기록 자체는 고치거나 지우지 않고, 해제하면 {@code released_at}만
 *       채운다. 블로그장이 바뀌어도 기록은 그대로 열람만 된다.
 * </ul>
 */
@Service
public class BlacklistService {

  private final BlogBlacklistRepository blacklistRepository;
  private final BlacklistInquiryRepository inquiryRepository;
  private final UserRepository userRepository;
  private final HashUtil hashUtil;
  private final NotificationService notificationService;
  private final Clock clock;

  public BlacklistService(
      BlogBlacklistRepository blacklistRepository,
      BlacklistInquiryRepository inquiryRepository,
      UserRepository userRepository,
      HashUtil hashUtil,
      NotificationService notificationService,
      Clock clock) {
    this.blacklistRepository = blacklistRepository;
    this.inquiryRepository = inquiryRepository;
    this.userRepository = userRepository;
    this.hashUtil = hashUtil;
    this.notificationService = notificationService;
    this.clock = clock;
  }

  /** 이 회원이 그 블로그 블랙리스트에 걸려 있는지 (T078) */
  @Transactional(readOnly = true)
  public boolean isBlacklisted(Long blogId, Long userId) {
    return !matches(blogId, userRepository.findById(userId).orElseThrow()).isEmpty();
  }

  /** 해제 문의를 남긴다 (BLG-12) */
  @Transactional
  public BlacklistInquiry inquire(Long blogId, Long userId, String rawMessage) {
    User user = userRepository.findById(userId).orElseThrow();
    BlogBlacklist record =
        matches(blogId, user).stream()
            .findFirst()
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "이 블로그 블랙리스트에 걸려 있지 않아요."));
    if (inquiryRepository.existsByBlacklistIdAndUserIdAndStatus(
        record.getId(), userId, InquiryStatus.PENDING)) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 문의를 남겼어요. 블로그장이 확인하면 알림으로 알려 드려요.");
    }
    String message = rawMessage == null ? null : rawMessage.strip();
    if (message != null
        && message.codePointCount(0, message.length()) > BlacklistInquiry.MESSAGE_MAX) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "문의 내용은 500자까지 쓸 수 있어요.");
    }
    boolean nameMatch =
        user.getName() != null && hashUtil.matches(user.getName(), record.getNameHash());
    boolean phoneMatch =
        user.getPhone() != null
            && hashUtil.hashPhone(user.getPhone()).equals(record.getPhoneHash());
    return inquiryRepository.save(
        new BlacklistInquiry(
            record,
            user,
            nameMatch,
            phoneMatch,
            message == null || message.isEmpty() ? null : message));
  }

  @Transactional(readOnly = true)
  public List<BlogBlacklist> records(Long blogId) {
    return blacklistRepository.findForBlog(blogId);
  }

  @Transactional(readOnly = true)
  public List<BlacklistInquiry> pendingInquiries(Long blogId) {
    return inquiryRepository.findForBlog(blogId, InquiryStatus.PENDING);
  }

  /** 해제. 기록에 해제 시각을 남기고 문의자에게 알린다 */
  @Transactional
  public void release(Long blogId, Long inquiryId, Long actorId) {
    BlacklistInquiry inquiry = pending(blogId, inquiryId);
    User actor = userRepository.getReferenceById(actorId);
    inquiry.handle(InquiryStatus.RELEASED, actor, now());
    inquiry.getBlacklist().release(actor, now());
    notifyResult(inquiry, actorId, "블랙리스트에서 해제되었어요. 이제 참여 신청을 할 수 있어요.");
  }

  @Transactional
  public void reject(Long blogId, Long inquiryId, Long actorId) {
    BlacklistInquiry inquiry = pending(blogId, inquiryId);
    inquiry.handle(InquiryStatus.REJECTED, userRepository.getReferenceById(actorId), now());
    notifyResult(inquiry, actorId, "블랙리스트 해제 문의가 거절되었어요.");
  }

  private List<BlogBlacklist> matches(Long blogId, User user) {
    String emailHash = user.getEmail() == null ? "-" : hashUtil.hashEmail(user.getEmail());
    String phoneHash = user.getPhone() == null ? "-" : hashUtil.hashPhone(user.getPhone());
    return blacklistRepository.findActiveMatches(blogId, emailHash, phoneHash);
  }

  private BlacklistInquiry pending(Long blogId, Long inquiryId) {
    BlacklistInquiry inquiry =
        inquiryRepository
            .findById(inquiryId)
            .filter(i -> i.getBlacklist().getBlog().getId().equals(blogId))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (!inquiry.isPending()) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 처리된 문의예요.");
    }
    return inquiry;
  }

  private void notifyResult(BlacklistInquiry inquiry, Long actorId, String message) {
    Long blogId = inquiry.getBlacklist().getBlog().getId();
    notificationService.notify(
        inquiry.getUser().getId(),
        actorId,
        NotificationType.BLACKLIST_INQUIRY_RESULT,
        NotificationTargetType.BLOG,
        blogId,
        "'" + inquiry.getBlacklist().getBlog().getName() + "' " + message);
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
