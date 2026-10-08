package com.blog.blog.api;

import com.blog.blog.domain.BlacklistInquiry;
import com.blog.blog.domain.BlogBlacklist;
import com.blog.blog.service.BlacklistService;
import com.blog.common.security.AuthUser;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 블랙리스트 열람과 해제 문의 (T079, BLG-11, BLG-12). 기록을 고치거나 지우는 API는 없다 (D-55). */
@RestController
@RequestMapping("/api/blogs/{blogId}")
public class BlacklistController {

  private final BlacklistService blacklistService;

  public BlacklistController(BlacklistService blacklistService) {
    this.blacklistService = blacklistService;
  }

  public record InquiryForm(String message) {}

  public record Saved(Long id) {}

  public record BlacklistRow(
      Long id,
      String nickname,
      String registeredBy,
      LocalDateTime createdAt,
      LocalDateTime releasedAt) {}

  public record InquiryRow(
      Long id,
      Long blacklistId,
      String nickname,
      boolean nameMatch,
      boolean phoneMatch,
      String message,
      LocalDateTime createdAt) {}

  public record BlacklistView(List<BlacklistRow> records, List<InquiryRow> inquiries) {}

  /** 걸린 사람이 남기는 해제 문의 */
  @PostMapping("/blacklist-inquiries")
  @ResponseStatus(HttpStatus.CREATED)
  public Saved inquire(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @RequestBody(required = false) InquiryForm form) {
    BlacklistInquiry inquiry =
        blacklistService.inquire(blogId, user.id(), form == null ? null : form.message());
    return new Saved(inquiry.getId());
  }

  /** 관리 화면: 기록과 대기 중인 문의 */
  @GetMapping("/blacklist")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
  @Transactional(readOnly = true)
  public BlacklistView view(@PathVariable Long blogId) {
    List<BlacklistRow> records =
        blacklistService.records(blogId).stream().map(BlacklistController::row).toList();
    List<InquiryRow> inquiries =
        blacklistService.pendingInquiries(blogId).stream()
            .map(
                i ->
                    new InquiryRow(
                        i.getId(),
                        i.getBlacklist().getId(),
                        i.getUser().getNickname(),
                        i.isNameMatch(),
                        i.isPhoneMatch(),
                        i.getMessage(),
                        i.getCreatedAt()))
            .toList();
    return new BlacklistView(records, inquiries);
  }

  @PostMapping("/blacklist-inquiries/{inquiryId}/release")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
  public ResponseEntity<Void> release(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @PathVariable Long inquiryId) {
    blacklistService.release(blogId, inquiryId, user.id());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/blacklist-inquiries/{inquiryId}/reject")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
  public ResponseEntity<Void> reject(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @PathVariable Long inquiryId) {
    blacklistService.reject(blogId, inquiryId, user.id());
    return ResponseEntity.noContent().build();
  }

  private static BlacklistRow row(BlogBlacklist b) {
    String nickname =
        b.getUser() == null || !b.getUser().isActive() || b.getUser().getNickname() == null
            ? "탈퇴한 회원"
            : b.getUser().getNickname();
    return new BlacklistRow(
        b.getId(),
        nickname,
        b.getRegisteredBy().getNickname(),
        b.getCreatedAt(),
        b.getReleasedAt());
  }
}
