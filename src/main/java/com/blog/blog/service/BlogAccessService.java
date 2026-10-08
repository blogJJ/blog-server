package com.blog.blog.service;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.BlogVisibility;
import com.blog.blog.domain.MemberSanction;
import com.blog.blog.domain.SanctionType;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.repository.MemberSanctionRepository;
import com.blog.common.domain.Suspensions;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그를 볼 수 있는지 확인한다 (T047, BLG-01). 블로그 첫 화면, 글 목록·글 읽기(US3)가 모두 여기를 거친다.
 *
 * <ul>
 *   <li>공개: 누구나. 일부 공개: 공유 링크로 온 사람과 멤버. 비공개: 멤버만.
 *   <li>숨김(ADM-02)·폐쇄된 블로그는 없는 블로그처럼 404. 폐쇄 예정은 평소처럼 보인다 (D-67).
 *   <li>그 블로그에서 정지된 멤버(BLG-13)는 끝나는 날짜를 알려 주고 막는다.
 * </ul>
 */
@Service
public class BlogAccessService {

  private static final DateTimeFormatter UNTIL_FORMAT =
      DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");

  private final BlogRepository blogRepository;
  private final BlogMemberRepository memberRepository;
  private final MemberSanctionRepository sanctionRepository;
  private final Clock clock;

  public BlogAccessService(
      BlogRepository blogRepository,
      BlogMemberRepository memberRepository,
      MemberSanctionRepository sanctionRepository,
      Clock clock) {
    this.blogRepository = blogRepository;
    this.memberRepository = memberRepository;
    this.sanctionRepository = sanctionRepository;
    this.clock = clock;
  }

  /** 볼 수 있는 블로그와 보는 사람의 멤버 행(없으면 비어 있음). */
  public record Access(Blog blog, Optional<BlogMember> membership, boolean viaShareLink) {}

  /** 주소로 찾아 확인한다. */
  @Transactional(readOnly = true)
  public Access checkBySlug(String slug, Long viewerId, String shareKey) {
    Blog blog =
        blogRepository
            .findBySlug(slug)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    return check(blog, viewerId, shareKey);
  }

  /** 번호로 찾아 확인한다. */
  @Transactional(readOnly = true)
  public Access checkById(Long blogId, Long viewerId, String shareKey) {
    Blog blog =
        blogRepository
            .findById(blogId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    return check(blog, viewerId, shareKey);
  }

  /**
   * @param viewerId 로그인한 회원. 비회원이면 null
   * @param shareKey 주소의 share 값. 없으면 null
   */
  public Access check(Blog blog, Long viewerId, String shareKey) {
    if (!blog.isOpen() || blog.isHidden()) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    Optional<BlogMember> membership =
        viewerId == null
            ? Optional.empty()
            : memberRepository.findByBlogIdAndUserId(blog.getId(), viewerId);
    LocalDateTime now = LocalDateTime.now(clock);
    membership.filter(m -> m.isSuspendedAt(now)).ifPresent(m -> rejectSuspended(m));

    boolean member = membership.isPresent();
    boolean viaLink = ShareLinkService.matches(blog, shareKey);
    boolean allowed =
        switch (blog.getVisibility()) {
          case PUBLIC -> true;
          case LINK_ONLY -> member || viaLink;
          case PRIVATE -> member;
        };
    if (!allowed) {
      throw new BusinessException(ErrorCode.FORBIDDEN, deniedMessage(blog.getVisibility()));
    }
    return new Access(blog, membership, viaLink && !member);
  }

  private static String deniedMessage(BlogVisibility visibility) {
    return visibility == BlogVisibility.LINK_ONLY
        ? "공유 링크로만 볼 수 있는 블로그예요. 받은 링크로 다시 들어와 주세요."
        : "비공개 블로그예요. 멤버만 볼 수 있어요.";
  }

  /** 정지된 멤버에게 기간과 사유를 알려 주고 막는다 (BLG-13). */
  private void rejectSuspended(BlogMember member) {
    LocalDateTime until = member.getSuspendedUntil();
    String message =
        !until.isBefore(Suspensions.PERMANENT)
            ? "이 블로그에서 영구 정지되어 들어갈 수 없어요."
            : "이 블로그에서 " + until.format(UNTIL_FORMAT) + "까지 정지되어 들어갈 수 없어요.";
    String reason =
        sanctionRepository
            .findFirstByBlogIdAndUserIdAndTypeOrderByIdDesc(
                member.getBlog().getId(), member.getUser().getId(), SanctionType.SUSPENSION)
            .map(MemberSanction::getReason)
            .orElse(null);
    if (reason != null) {
      message += " 사유: " + reason;
    }
    throw new BusinessException(ErrorCode.FORBIDDEN, message);
  }
}
