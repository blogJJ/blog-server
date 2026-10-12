package com.blog.admin.api;

import com.blog.admin.service.AdminNoticeService;
import com.blog.board.domain.Post;
import com.blog.common.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 메인 공지 올리기 (T125, BRD-10). 읽기는 {@code GET /api/notices} */
@RestController
public class AdminNoticeController {

  /**
   * @param content 마크다운, 5,000자까지
   */
  public record NoticeForm(String title, String content) {}

  public record Created(Long id) {}

  private final AdminNoticeService noticeService;

  public AdminNoticeController(AdminNoticeService noticeService) {
    this.noticeService = noticeService;
  }

  @PostMapping("/api/admin/notices")
  @ResponseStatus(HttpStatus.CREATED)
  public Created create(@AuthenticationPrincipal AuthUser admin, @RequestBody NoticeForm form) {
    Post post = noticeService.post(admin.id(), form.title(), form.content());
    return new Created(post.getId());
  }
}
