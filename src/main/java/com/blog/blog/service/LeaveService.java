package com.blog.blog.service;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogJoinRequest;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.repository.BlogJoinRequestRepository;
import com.blog.blog.repository.BlogManagerPermissionRepository;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.blog.repository.BlogRepository;
import com.blog.board.repository.PostRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 떠나기 (T091, BLG-07, D-33)와 회원 탈퇴 때 멤버 정리 (T092, USR-05).
 *
 * <ul>
 *   <li>블로그장은 떠날 수 없다. 위임하거나 폐쇄해야 한다 (BLG-07, D-15).
 *   <li>그 블로그에서 정지 중인 멤버는 떠날 수 없다. 떠났다가 다시 참여해 정지를 피하지 못하게 한다.
 *   <li>떠나면 멤버 행과 부블로그장 권한을 지우고, 그 블로그의 글은 작성자가 "탈퇴한 계정"으로 바뀐다 (D-33). 그 사람에게 온 위임 요청은 취소한다.
 * </ul>
 */
@Service
public class LeaveService {

  private final BlogRepository blogRepository;
  private final BlogMemberRepository memberRepository;
  private final BlogManagerPermissionRepository permissionRepository;
  private final BlogJoinRequestRepository requestRepository;
  private final PostRepository postRepository;
  private final TransferService transferService;
  private final Clock clock;

  public LeaveService(
      BlogRepository blogRepository,
      BlogMemberRepository memberRepository,
      BlogManagerPermissionRepository permissionRepository,
      BlogJoinRequestRepository requestRepository,
      PostRepository postRepository,
      TransferService transferService,
      Clock clock) {
    this.blogRepository = blogRepository;
    this.memberRepository = memberRepository;
    this.permissionRepository = permissionRepository;
    this.requestRepository = requestRepository;
    this.postRepository = postRepository;
    this.transferService = transferService;
    this.clock = clock;
  }

  /** 블로그 떠나기 (T091) */
  @Transactional
  public void leave(Long blogId, Long userId) {
    Blog blog =
        blogRepository
            .findById(blogId)
            .filter(Blog::isOpen)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    BlogMember member =
        memberRepository
            .findByBlogIdAndUserId(blog.getId(), userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이 블로그의 멤버가 아니에요."));
    if (member.isOwner()) {
      throw new BusinessException(
          ErrorCode.FORBIDDEN, "블로그장은 블로그를 떠날 수 없어요. 다른 멤버에게 위임하거나 블로그를 폐쇄해 주세요.");
    }
    if (member.isSuspendedAt(LocalDateTime.now(clock))) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "이 블로그에서 정지 중에는 블로그를 떠날 수 없어요.");
    }
    remove(member);
    postRepository.hideAuthor(blogId, userId);
    transferService.cancelForLeavingMember(blogId, userId);
  }

  /**
   * 회원 탈퇴 때: 블로그장이 아닌 모든 멤버 행을 지우고, 대기 중인 참여 신청과 받은 위임 요청을 취소한다. 블로그장 행은 폐쇄된 블로그에만 남아 있으므로 그대로 둔다.
   * 글은 "탈퇴한 회원"으로 보이므로 author_hidden은 바꾸지 않는다 (D-05).
   */
  @Transactional
  public void leaveAll(Long userId) {
    for (BlogMember m : memberRepository.findByUserId(userId)) {
      if (!m.isOwner()) {
        remove(m);
      }
    }
    LocalDateTime now = LocalDateTime.now(clock);
    for (BlogJoinRequest r : requestRepository.findPendingWithBlogByUserId(userId)) {
      r.cancel(now);
    }
    transferService.cancelForLeavingMember(null, userId);
  }

  private void remove(BlogMember member) {
    Long blogId = member.getBlog().getId();
    permissionRepository.deleteByBlogMemberId(member.getId());
    memberRepository.delete(member);
    memberRepository.flush();
    blogRepository.decreaseMemberCount(blogId);
  }
}
