package com.blog.common.security;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.common.domain.Suspensions;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 계정 상태로 블로그 활동을 막는다 (T018). 서비스에서 쓰기 전에 {@code accountGuard.check(userId, Activity.WRITE_POST)}로
 * 부른다.
 *
 * <ul>
 *   <li>메인 관리자(role = ADMIN)도 일반 회원과 같은 활동을 모두 한다 (D-122, D-90을 바꿈).
 *   <li>계정 정지 중(users.suspended_until이 지금보다 뒤): 로그인과 읽기만. 글·댓글·좋아요·팔로우·구독·블로그 만들기·참여 신청을 못 한다
 *       (ADM-08).
 * </ul>
 */
@Component("accountGuard")
public class AccountGuard {

  private static final DateTimeFormatter UNTIL_FORMAT =
      DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");

  /** 계정 정지 중에 막는 활동 */
  public enum Activity {
    CREATE_BLOG,
    JOIN_BLOG,
    WRITE_POST,
    WRITE_COMMENT,
    LIKE,
    FOLLOW,
    SUBSCRIBE
  }

  private final UserRepository userRepository;
  private final Clock clock;

  public AccountGuard(UserRepository userRepository, Clock clock) {
    this.userRepository = userRepository;
    this.clock = clock;
  }

  /** 할 수 없으면 BusinessException(403)을 던진다. 회원이 없거나 탈퇴했으면 401. */
  public void check(Long userId, Activity activity) {
    User user =
        userRepository
            .findById(userId)
            .filter(User::isActive)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    check(user, activity);
  }

  /** 이미 읽어 둔 회원으로 확인한다. */
  // activity는 지금은 정지 여부만 보지만, 활동마다 막을 수 있게 호출하는 쪽이 넘기는 약속이라 남겨 둔다
  @SuppressWarnings("java:S1172")
  public void check(User user, Activity activity) {
    LocalDateTime now = LocalDateTime.now(clock);
    if (user.isSuspendedAt(now)) {
      throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED, suspendedMessage(user));
    }
  }

  /** SpEL에서 쓸 때: {@code @PreAuthorize("@accountGuard.can('WRITE_POST')")}. 지금 로그인한 회원 기준. */
  public boolean can(String activity) {
    Activity a = Activity.valueOf(activity);
    return CurrentUser.id()
        .flatMap(userRepository::findById)
        .filter(User::isActive)
        .map(
            user -> {
              try {
                check(user, a);
                return true;
              } catch (BusinessException e) {
                return false;
              }
            })
        .orElse(false);
  }

  private static String suspendedMessage(User user) {
    LocalDateTime until = user.getSuspendedUntil();
    if (!until.isBefore(Suspensions.PERMANENT)) {
      return "계정이 영구 정지되어 읽기만 할 수 있어요.";
    }
    return "계정이 " + until.format(UNTIL_FORMAT) + "까지 정지되어 읽기만 할 수 있어요.";
  }
}
