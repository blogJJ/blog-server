package com.blog.admin.service;

import com.blog.admin.domain.AdminAction;
import com.blog.admin.domain.AdminTargetType;
import com.blog.admin.repository.AdminQueryRepository;
import com.blog.auth.domain.User;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.CloseReason;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.service.BlogCloseService;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.web.PageResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 관리 (T118, ADM-02, D-96). 전체 블로그 검색(숨김·폐쇄 포함), 블로그장·멤버 수·글 수 보기, 숨김·숨김 해제, 강제 폐쇄.
 *
 * <ul>
 *   <li>숨긴 블로그는 목록·검색에서 빠지고 주소로 들어와도 없는 블로그처럼 404 (BlogAccessService).
 *   <li>강제 폐쇄도 블로그장 폐쇄(BLG-09)처럼 BlogCloseService로 누른 날짜 + 7일 04:00에 폐쇄 예정이 되고 같은 알림이 간다. 7일 안에 그
 *       블로그의 블로그장이 철회할 수 있다 (D-96).
 * </ul>
 */
@Service
public class AdminBlogService {

  /** 목록 한 줄 */
  public record BlogRow(
      Long id,
      String slug,
      String name,
      Long ownerId,
      String ownerNickname,
      LocalDateTime ownerSuspendedUntil,
      int memberCount,
      int postCount,
      String visibility,
      String status,
      boolean hidden,
      LocalDateTime closeScheduledAt,
      String closeReason,
      LocalDateTime createdAt) {}

  private final BlogRepository blogRepository;
  private final AdminQueryRepository queryRepository;
  private final BlogCloseService closeService;
  private final AdminActionLogger actionLogger;
  private final Clock clock;

  public AdminBlogService(
      BlogRepository blogRepository,
      AdminQueryRepository queryRepository,
      BlogCloseService closeService,
      AdminActionLogger actionLogger,
      Clock clock) {
    this.blogRepository = blogRepository;
    this.queryRepository = queryRepository;
    this.closeService = closeService;
    this.actionLogger = actionLogger;
    this.clock = clock;
  }

  /** 이름·주소로 검색 */
  @Transactional(readOnly = true)
  public PageResponse<BlogRow> search(String q, int page) {
    LocalDateTime now = LocalDateTime.now(clock);
    return PageResponse.of(
        queryRepository.searchBlogs(AdminSearch.like(q), AdminSearch.page(page)),
        list -> list.stream().map(b -> row(b, now)).toList());
  }

  @Transactional
  public void hide(Long blogId, Long adminId, String reason) {
    Blog blog = openBlog(blogId);
    if (blog.isHidden()) {
      throw new BusinessException(ErrorCode.CONFLICT, "이미 숨긴 블로그예요.");
    }
    blog.hide();
    actionLogger.log(adminId, AdminAction.HIDE_BLOG, AdminTargetType.BLOG, blogId, reason);
  }

  @Transactional
  public void unhide(Long blogId, Long adminId, String reason) {
    Blog blog = openBlog(blogId);
    if (!blog.isHidden()) {
      throw new BusinessException(ErrorCode.CONFLICT, "숨긴 블로그가 아니에요.");
    }
    blog.unhide();
    actionLogger.log(adminId, AdminAction.UNHIDE_BLOG, AdminTargetType.BLOG, blogId, reason);
  }

  /** 강제 폐쇄 (7일 유예). 사유는 활동 기록에 남는다 */
  @Transactional
  public void close(Long blogId, Long adminId, String reason) {
    String why = AdminReasons.valid(reason);
    openBlog(blogId);
    closeService.scheduleClose(blogId, CloseReason.ADMIN, adminId);
    actionLogger.log(adminId, AdminAction.CLOSE_BLOG, AdminTargetType.BLOG, blogId, why);
  }

  private Blog openBlog(Long blogId) {
    return blogRepository
        .findById(blogId)
        .filter(Blog::isOpen)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static BlogRow row(Blog b, LocalDateTime now) {
    User owner = b.getOwner();
    return new BlogRow(
        b.getId(),
        b.getSlug(),
        b.getName(),
        owner.getId(),
        owner.getNickname(),
        owner.isSuspendedAt(now) ? owner.getSuspendedUntil() : null,
        b.getMemberCount(),
        b.getPostCount(),
        b.getVisibility().name(),
        b.getStatus().name(),
        b.isHidden(),
        b.getCloseScheduledAt(),
        b.getCloseReason() == null ? null : b.getCloseReason().name(),
        b.getCreatedAt());
  }
}
