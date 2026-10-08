package com.blog.blog.api;

import com.blog.blog.service.BlogQueryService;
import com.blog.blog.service.BlogQueryService.JoinRequestRow;
import com.blog.blog.service.JoinService;
import com.blog.common.security.AuthUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 참여 신청·승인·거절·취소 (T050, T051, BLG-04, BLG-05). */
@RestController
@RequestMapping("/api/blogs/{blogId}")
public class JoinController {

  private final JoinService joinService;
  private final BlogQueryService queryService;

  public JoinController(JoinService joinService, BlogQueryService queryService) {
    this.joinService = joinService;
    this.queryService = queryService;
  }

  /**
   * @param joined true면 바로 멤버가 됐다(자유 참여). false면 승인 대기
   */
  public record JoinResponse(boolean joined, Long requestId, String message) {}

  /** 참여 신청. 일부 공개 블로그는 공유 링크의 share 값을 같이 보낸다 */
  @PostMapping("/join")
  public JoinResponse join(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @RequestParam(required = false) String share) {
    JoinService.JoinResult result = joinService.join(blogId, user.id(), share);
    if (result.joined()) {
      return new JoinResponse(true, null, "블로그에 참여했어요.");
    }
    return new JoinResponse(false, result.request().getId(), "참여 신청을 보냈어요. 승인되면 알림으로 알려 드려요.");
  }

  /** 대기 중인 신청 목록 (관리 화면) */
  @GetMapping("/join-requests")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
  public List<JoinRequestRow> pending(@PathVariable Long blogId) {
    return queryService.pendingRequests(blogId);
  }

  @PostMapping("/join-requests/{requestId}/approve")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
  public ResponseEntity<Void> approve(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @PathVariable Long requestId) {
    joinService.approve(blogId, requestId, user.id());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/join-requests/{requestId}/reject")
  @PreAuthorize("@blogAuthz.hasPermission(#blogId, 'MANAGE_MEMBERS')")
  public ResponseEntity<Void> reject(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @PathVariable Long requestId) {
    joinService.reject(blogId, requestId, user.id());
    return ResponseEntity.noContent().build();
  }

  /** 신청한 사람이 취소 */
  @DeleteMapping("/join-requests/{requestId}")
  public ResponseEntity<Void> cancel(
      @AuthenticationPrincipal AuthUser user,
      @PathVariable Long blogId,
      @PathVariable Long requestId) {
    joinService.cancel(blogId, requestId, user.id());
    return ResponseEntity.noContent().build();
  }
}
