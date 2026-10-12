package com.blog.blog.api;

import com.blog.blog.domain.BlogOwnerTransfer;
import com.blog.blog.service.TransferService;
import com.blog.blog.service.TransferService.TransferRow;
import com.blog.common.security.AuthUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 블로그장 위임 (T087, BLG-08, D-16, D-20, D-31). 요청은 블로그장만, 수락·거절은 받는 멤버만. 메인 관리자는 다른 블로그장 권한을 모두 갖지만
 * 위임은 하지 못한다 (D-122).
 */
@RestController
public class TransferController {

  private final TransferService transferService;

  public TransferController(TransferService transferService) {
    this.transferService = transferService;
  }

  public record TransferForm(Long toUserId) {}

  public record CreatedResponse(Long id) {}

  /** 위임 요청 */
  @PostMapping("/api/blogs/{blogId}/transfers")
  @PreAuthorize("@blogAuthz.isRealOwner(#blogId)")
  public ResponseEntity<CreatedResponse> request(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @RequestBody TransferForm form) {
    BlogOwnerTransfer t = transferService.request(blogId, user.id(), form.toUserId());
    return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedResponse(t.getId()));
  }

  /** 관리 화면: 대기 중인 요청. 없으면 204 */
  @GetMapping("/api/blogs/{blogId}/transfers/pending")
  @PreAuthorize("@blogAuthz.isRealOwner(#blogId)")
  public ResponseEntity<TransferRow> pending(@PathVariable Long blogId) {
    return transferService
        .pendingOfBlog(blogId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  /** 관리 화면: 대기 중인 요청을 블로그장이 취소한다 (D-124) */
  @PostMapping("/api/blogs/{blogId}/transfers/pending/cancel")
  @PreAuthorize("@blogAuthz.isRealOwner(#blogId)")
  public ResponseEntity<Void> cancel(
      @AuthenticationPrincipal AuthUser user, @PathVariable Long blogId) {
    transferService.cancelByOwner(blogId, user.id());
    return ResponseEntity.noContent().build();
  }

  /** 위임 수락 화면: 내가 받은 대기 중인 요청 */
  @GetMapping("/api/me/transfers")
  public List<TransferRow> mine(@AuthenticationPrincipal AuthUser user) {
    return transferService.pendingForMe(user.id());
  }

  @PostMapping("/api/transfers/{id}/accept")
  public ResponseEntity<Void> accept(
      @AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
    transferService.accept(id, user.id());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/transfers/{id}/reject")
  public ResponseEntity<Void> reject(
      @AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
    transferService.reject(id, user.id());
    return ResponseEntity.noContent().build();
  }
}
