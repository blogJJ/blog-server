package com.blog.admin.service;

import com.blog.admin.domain.AdminAction;
import com.blog.admin.domain.AdminTargetType;
import com.blog.admin.repository.AdminQueryRepository;
import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogVisibility;
import com.blog.blog.domain.JoinPolicy;
import com.blog.blog.repository.BlogRepository;
import com.blog.board.domain.Post;
import com.blog.board.repository.PostRepository;
import com.blog.board.service.ContentRenderer;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.social.domain.NotificationTargetType;
import com.blog.social.domain.NotificationType;
import com.blog.social.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메인 공지 (T125, BRD-10). 공지는 {@code main} 블로그의 글({@code is_notice = TRUE})로 저장하고 전체 회원에게 "공지사항" 알림을
 * 보낸다.
 *
 * <ul>
 *   <li>{@code main} 블로그는 첫 공지를 올릴 때 만든다. 블로그장 칸은 NOT NULL이라 처음 올린 관리자로 채우지만, 관리자는 블로그 멤버가 되지
 *       않는다(D-90). 숨김·비공개라 블로그 목록·검색·블로그 화면에는 나오지 않고, 공지는 {@code /api/notices}와 공지 화면에서 읽는다.
 *   <li>{@code main}은 블로그 주소 예약어라 회원이 같은 주소의 블로그를 만들 수 없다.
 *   <li>알림 대상은 탈퇴하지 않은 일반 회원. 공지 알림은 회원이 끌 수 있다.
 * </ul>
 */
@Service
public class AdminNoticeService {

  public static final String MAIN_SLUG = "main";

  private final BlogRepository blogRepository;
  private final PostRepository postRepository;
  private final UserRepository userRepository;
  private final AdminQueryRepository queryRepository;
  private final NotificationService notificationService;
  private final AdminActionLogger actionLogger;

  public AdminNoticeService(
      BlogRepository blogRepository,
      PostRepository postRepository,
      UserRepository userRepository,
      AdminQueryRepository queryRepository,
      NotificationService notificationService,
      AdminActionLogger actionLogger) {
    this.blogRepository = blogRepository;
    this.postRepository = postRepository;
    this.userRepository = userRepository;
    this.queryRepository = queryRepository;
    this.notificationService = notificationService;
    this.actionLogger = actionLogger;
  }

  /** 공지를 올린다 */
  @Transactional
  public Post post(Long adminId, String rawTitle, String rawContent) {
    String title = rawTitle == null ? "" : rawTitle.strip();
    int length = title.codePointCount(0, title.length());
    if (length < 1 || length > Post.TITLE_MAX) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "제목은 1~30자로 입력해 주세요.");
    }
    String content = ContentRenderer.validate(rawContent);
    User admin = userRepository.getReferenceById(adminId);
    Blog main = blogRepository.findBySlug(MAIN_SLUG).orElseGet(() -> createMain(admin));
    Post post = postRepository.save(new Post(main, admin, null, title, content, true));
    blogRepository.addPostCount(main.getId(), 1);
    actionLogger.log(adminId, AdminAction.POST_NOTICE, AdminTargetType.POST, post.getId(), title);
    notificationService.notifyAll(
        queryRepository.activeMemberIds(),
        adminId,
        NotificationType.NOTICE,
        NotificationTargetType.POST,
        post.getId(),
        "공지사항: " + title);
    return post;
  }

  private Blog createMain(User admin) {
    Blog blog =
        new Blog(
            admin, MAIN_SLUG, "공지사항", "메인 관리자 공지", BlogVisibility.PRIVATE, JoinPolicy.APPROVAL);
    blog.hide();
    return blogRepository.save(blog);
  }
}
