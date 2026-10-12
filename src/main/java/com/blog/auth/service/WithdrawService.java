package com.blog.auth.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.SocialAccountRepository;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogStatus;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.service.LeaveService;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴 (T092, USR-05, D-05, D-15, D-18).
 *
 * <ul>
 *   <li>비밀번호를 다시 확인한다. 소셜 로그인으로만 가입해 비밀번호가 없으면 건너뛴다 (D-128).
 *   <li>블로그장인 운영 중·폐쇄 예정 블로그가 하나라도 있으면 막고, 그 목록을 돌려준다. 폐쇄 예정 중에도 막는다(철회할 수 있는 동안은 블로그장이 있어야 함,
 *       D-18).
 *   <li>통과하면 status WITHDRAWN, 이메일은 바로 NULL(같은 이메일로 바로 재가입), 로그인은 모두 끝낸다. 이름·닉네임·전화번호·사진은 30일 뒤 배치가
 *       지운다.
 *   <li>소셜 로그인 연결은 모두 지운다. 같은 소셜 계정으로 새로 가입할 수 있다 (D-128).
 *   <li>블로그장이 아닌 멤버 자리에서는 모두 빠지고, 대기 중인 참여 신청과 받은 위임 요청은 취소한다. 쓴 글은 지우지 않고 "탈퇴한 회원"으로 남긴다 (D-05).
 * </ul>
 */
@Service
public class WithdrawService {

  /** 탈퇴를 막은 블로그 한 줄 */
  public record OwnedBlog(Long id, String slug, String name, BlogStatus status) {}

  /** 블로그장인 블로그가 남아 있어 탈퇴를 막았다. 응답에 그 목록을 붙인다. */
  public static class WithdrawBlockedException extends BusinessException {

    private final transient List<OwnedBlog> blogs;

    public WithdrawBlockedException(List<OwnedBlog> blogs) {
      super(ErrorCode.WITHDRAW_BLOCKED);
      this.blogs = blogs;
    }

    public List<OwnedBlog> getBlogs() {
      return blogs;
    }
  }

  private final UserRepository userRepository;
  private final SocialAccountRepository socialAccountRepository;
  private final BlogRepository blogRepository;
  private final LeaveService leaveService;
  private final LoginSessionService sessionService;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  public WithdrawService(
      UserRepository userRepository,
      SocialAccountRepository socialAccountRepository,
      BlogRepository blogRepository,
      LeaveService leaveService,
      LoginSessionService sessionService,
      PasswordEncoder passwordEncoder,
      Clock clock) {
    this.userRepository = userRepository;
    this.socialAccountRepository = socialAccountRepository;
    this.blogRepository = blogRepository;
    this.leaveService = leaveService;
    this.sessionService = sessionService;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
  }

  /** 탈퇴 전에 막히는 블로그가 있는지 미리 본다 (탈퇴 화면). */
  @Transactional(readOnly = true)
  public List<OwnedBlog> blockingBlogs(Long userId) {
    return blogRepository.findOpenOwnedBy(userId).stream().map(WithdrawService::row).toList();
  }

  /**
   * @throws BusinessException 비밀번호가 틀리면 INVALID_INPUT
   * @throws WithdrawBlockedException 블로그장인 운영 중·폐쇄 예정 블로그가 있으면
   */
  @Transactional
  public void withdraw(Long userId, String password) {
    // 회원 행을 잠가 탈퇴 확인과 블로그 만들기(같은 행을 잠금, BLG-10)가 엇갈리지 않게 한다
    User user =
        userRepository
            .findByIdForUpdate(userId)
            .filter(User::isActive)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    // 소셜 로그인으로만 가입해 비밀번호가 없으면 확인할 비밀번호가 없다. 화면의 확인 체크와 되묻기로 대신한다 (D-128)
    if (user.hasPassword()
        && (password == null || !passwordEncoder.matches(password, user.getPasswordHash()))) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "비밀번호가 맞지 않아요.");
    }
    List<Blog> owned = blogRepository.findOpenOwnedBy(userId);
    if (!owned.isEmpty()) {
      throw new WithdrawBlockedException(owned.stream().map(WithdrawService::row).toList());
    }
    leaveService.leaveAll(userId);
    socialAccountRepository.deleteAllOf(userId);
    user.withdraw(LocalDateTime.now(clock));
    sessionService.revokeAll(userId);
  }

  private static OwnedBlog row(Blog b) {
    return new OwnedBlog(b.getId(), b.getSlug(), b.getName(), b.getStatus());
  }
}
