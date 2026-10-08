package com.blog.social.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.BlogJoinRequest;
import com.blog.blog.domain.JoinRequestStatus;
import com.blog.blog.repository.BlogJoinRequestRepository;
import com.blog.board.service.ImageService;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.social.domain.UserBlock;
import com.blog.social.repository.UserBlockRepository;
import com.blog.social.repository.UserFollowRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 차단 (T104, T105, SOC-05, D-36).
 *
 * <ul>
 *   <li>차단하면 서로의 팔로우를 풀고, 차단이 남아 있는 동안 어느 쪽도 상대를 팔로우할 수 없다.
 *   <li>차단한 회원의 글·댓글은 차단한 사람에게만 목록·피드·검색에서 안 보인다. 같은 블로그 멤버라면 그 블로그 안에서는 보인다.
 *   <li>차단한 회원이 일으킨 알림(댓글·좋아요·팔로우 등)은 만들지 않는다.
 *   <li>내가 블로그장인 블로그에 그 회원이 낸 대기 중 참여 신청은 바로 거절한다. 새 신청도 자동으로 거절된다 (JoinService).
 * </ul>
 */
@Service
public class BlockService {

  /** 빈 목록을 {@code not in}에 넣으면 SQL이 깨져서, 없는 번호 하나를 대신 넣는다. */
  public static final List<Long> NONE = List.of(-1L);

  public record BlockedRow(Long userId, String nickname, String profileImage, LocalDateTime blockedAt) {}

  private final UserBlockRepository blockRepository;
  private final UserFollowRepository followRepository;
  private final UserRepository userRepository;
  private final BlogJoinRequestRepository requestRepository;
  private final Clock clock;

  public BlockService(
      UserBlockRepository blockRepository,
      UserFollowRepository followRepository,
      UserRepository userRepository,
      BlogJoinRequestRepository requestRepository,
      Clock clock) {
    this.blockRepository = blockRepository;
    this.followRepository = followRepository;
    this.userRepository = userRepository;
    this.requestRepository = requestRepository;
    this.clock = clock;
  }

  @Transactional
  public void block(Long me, Long targetId) {
    if (me.equals(targetId)) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "나 자신은 차단할 수 없어요.");
    }
    User target =
        userRepository.findById(targetId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    blockRepository.insertIgnore(me, target.getId());
    followRepository.deleteBetween(me, target.getId());
    User handler = userRepository.getReferenceById(me);
    LocalDateTime now = LocalDateTime.now(clock);
    for (BlogJoinRequest r :
        requestRepository.findPendingToOwnerBlogs(target.getId(), me, JoinRequestStatus.PENDING)) {
      r.reject(handler, now);
    }
  }

  @Transactional
  public void unblock(Long me, Long targetId) {
    blockRepository.deletePair(me, targetId);
  }

  @Transactional(readOnly = true)
  public boolean isBlocking(Long me, Long targetId) {
    return me != null && blockRepository.existsByBlockerIdAndBlockedId(me, targetId);
  }

  /** 내 차단 목록 (내 정보 화면에서 풀 때 쓴다) */
  @Transactional(readOnly = true)
  public List<BlockedRow> myBlocks(Long me) {
    List<BlockedRow> rows = new ArrayList<>();
    for (UserBlock b : blockRepository.findWithBlockedByBlockerId(me)) {
      User u = b.getBlocked();
      rows.add(
          new BlockedRow(
              u.getId(),
              u.isActive() ? u.getNickname() : "탈퇴한 계정",
              u.isActive() ? ImageService.url(u.getProfileImage()) : null,
              b.getCreatedAt()));
    }
    return rows;
  }

  /**
   * 목록·피드·검색에서 뺄 작성자 번호. 비회원이거나 차단한 회원이 없으면 {@link #NONE}이라 쿼리의 {@code not in}에 그대로 넣을 수 있다.
   */
  @Transactional(readOnly = true)
  public List<Long> hiddenAuthorIds(Long viewerId) {
    if (viewerId == null) {
      return NONE;
    }
    List<Long> ids = blockRepository.findBlockedIds(viewerId);
    return ids.isEmpty() ? NONE : ids;
  }
}
