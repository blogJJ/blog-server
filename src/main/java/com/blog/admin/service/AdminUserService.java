package com.blog.admin.service;

import com.blog.admin.domain.AdminAction;
import com.blog.admin.domain.AdminTargetType;
import com.blog.admin.repository.AdminQueryRepository;
import com.blog.admin.repository.OwnerSanctionRepository;
import com.blog.admin.repository.UserSanctionRepository;
import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.board.service.ImageService;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.privacy.Masking;
import com.blog.common.web.PageResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 관리 (T117, ADM-01, ADM-06, 4.4). 이메일·전화번호는 기본으로 가려서 주고, "전체 보기"를 누르면 원문을 주며 VIEW_PRIVATE_INFO로
 * 기록한다. 회원 정지·강제 퇴장은 블로그장이 하고, 메인 관리자는 신고에 따른 계정 제재(ADM-08)와 그 해제만 한다 (D-44, D-99).
 */
@Service
public class AdminUserService {

  /** 목록 한 줄. 이메일·전화번호는 가린 값 */
  public record UserRow(
      Long id,
      String email,
      String name,
      String nickname,
      String phone,
      String role,
      String status,
      LocalDateTime suspendedUntil,
      LocalDateTime createdAt) {}

  /** 회원 상세 */
  public record UserDetail(
      UserRow user,
      String bio,
      String profileImage,
      LocalDateTime withdrawnAt,
      List<BlogRow> blogs,
      List<AccountSanctionRow> accountSanctions,
      List<OwnerSanctionRow> ownerSanctions) {}

  public record BlogRow(Long blogId, String slug, String name, String role, String status) {}

  public record AccountSanctionRow(
      Long id,
      String type,
      Integer suspendDays,
      LocalDateTime endsAt,
      String reason,
      Long reportId,
      String admin,
      LocalDateTime releasedAt,
      LocalDateTime createdAt) {}

  public record OwnerSanctionRow(
      Long id,
      Long blogId,
      String blogName,
      String type,
      String reason,
      Long reportId,
      String admin,
      LocalDateTime createdAt) {}

  /** "전체 보기" 응답: 원문 */
  public record PrivateInfo(String email, String phone) {}

  private final UserRepository userRepository;
  private final AdminQueryRepository queryRepository;
  private final UserSanctionRepository userSanctionRepository;
  private final OwnerSanctionRepository ownerSanctionRepository;
  private final AdminActionLogger actionLogger;

  public AdminUserService(
      UserRepository userRepository,
      AdminQueryRepository queryRepository,
      UserSanctionRepository userSanctionRepository,
      OwnerSanctionRepository ownerSanctionRepository,
      AdminActionLogger actionLogger) {
    this.userRepository = userRepository;
    this.queryRepository = queryRepository;
    this.userSanctionRepository = userSanctionRepository;
    this.ownerSanctionRepository = ownerSanctionRepository;
    this.actionLogger = actionLogger;
  }

  /** 이메일·닉네임·이름으로 검색. 탈퇴 회원 포함 */
  @Transactional(readOnly = true)
  public PageResponse<UserRow> search(String q, int page) {
    return PageResponse.of(
        queryRepository.searchUsers(AdminSearch.like(q), AdminSearch.page(page)),
        list -> list.stream().map(AdminUserService::row).toList());
  }

  @Transactional(readOnly = true)
  public UserDetail detail(Long userId) {
    User user = load(userId);
    List<BlogRow> blogs =
        queryRepository.membershipsOf(userId).stream()
            .map(
                m ->
                    new BlogRow(
                        m.getBlog().getId(),
                        m.getBlog().getSlug(),
                        m.getBlog().getName(),
                        m.getRole().name(),
                        m.getBlog().getStatus().name()))
            .toList();
    List<AccountSanctionRow> accountSanctions =
        userSanctionRepository.findHistory(userId).stream()
            .map(
                s ->
                    new AccountSanctionRow(
                        s.getId(),
                        s.getType().name(),
                        s.getSuspendDays(),
                        s.getEndsAt(),
                        s.getReason(),
                        s.getReportId(),
                        s.getAdmin().getNickname(),
                        s.getReleasedAt(),
                        s.getCreatedAt()))
            .toList();
    List<OwnerSanctionRow> ownerSanctions =
        ownerSanctionRepository.findHistory(userId).stream()
            .map(
                s ->
                    new OwnerSanctionRow(
                        s.getId(),
                        s.getBlog().getId(),
                        s.getBlog().getName(),
                        s.getType().name(),
                        s.getReason(),
                        s.getReportId(),
                        s.getAdmin().getNickname(),
                        s.getCreatedAt()))
            .toList();
    return new UserDetail(
        row(user),
        user.getBio(),
        ImageService.url(user.getProfileImage()),
        user.getWithdrawnAt(),
        blogs,
        accountSanctions,
        ownerSanctions);
  }

  /** "전체 보기": 원문을 주고 기록한다 (ADM-06) */
  @Transactional
  public PrivateInfo reveal(Long userId, Long adminId) {
    User user = load(userId);
    actionLogger.log(adminId, AdminAction.VIEW_PRIVATE_INFO, AdminTargetType.USER, userId, null);
    return new PrivateInfo(user.getEmail(), user.getPhone());
  }

  private User load(Long userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private static UserRow row(User u) {
    return new UserRow(
        u.getId(),
        Masking.email(u.getEmail()),
        u.getName(),
        u.getNickname(),
        Masking.phone(u.getPhone()),
        u.getRole().name(),
        u.getStatus().name(),
        u.getSuspendedUntil(),
        u.getCreatedAt());
  }
}
