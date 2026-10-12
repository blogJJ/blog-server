package com.blog.main.api;

import com.blog.common.web.PageResponse;
import com.blog.main.service.NoticeQueryService;
import com.blog.main.service.NoticeQueryService.NoticeDetail;
import com.blog.main.service.NoticeQueryService.NoticeSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 메인 공지 읽기 (BRD-10). 비회원도 읽는다. 올리기는 {@code POST /api/admin/notices} */
@RestController
public class NoticeController {

  private final NoticeQueryService noticeQueryService;

  public NoticeController(NoticeQueryService noticeQueryService) {
    this.noticeQueryService = noticeQueryService;
  }

  @GetMapping("/api/notices")
  public PageResponse<NoticeSummary> list(@RequestParam(defaultValue = "1") int page) {
    return noticeQueryService.list(page);
  }

  @GetMapping("/api/notices/{id}")
  public NoticeDetail detail(@PathVariable Long id) {
    return noticeQueryService.detail(id);
  }
}
