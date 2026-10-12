package com.blog.main.service;

import com.blog.admin.service.AdminNoticeService;
import com.blog.board.domain.Post;
import com.blog.board.service.ContentRenderer;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.web.PageResponse;
import com.blog.main.repository.NoticeRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 메인 공지 읽기 (BRD-10). 누구나 읽는다. 본문은 글과 같이 걸러낸 HTML로 준다. */
@Service
public class NoticeQueryService {

  public static final int PAGE_SIZE = 20;

  public record NoticeSummary(Long id, String title, LocalDateTime createdAt) {}

  public record NoticeDetail(Long id, String title, String html, LocalDateTime createdAt) {}

  private final NoticeRepository noticeRepository;
  private final ContentRenderer renderer;

  public NoticeQueryService(NoticeRepository noticeRepository, ContentRenderer renderer) {
    this.noticeRepository = noticeRepository;
    this.renderer = renderer;
  }

  @Transactional(readOnly = true)
  public PageResponse<NoticeSummary> list(int page) {
    return PageResponse.of(
        noticeRepository.findNotices(
            AdminNoticeService.MAIN_SLUG, PageRequest.of(Math.max(page, 1) - 1, PAGE_SIZE)),
        list ->
            list.stream()
                .map(p -> new NoticeSummary(p.getId(), p.getTitle(), p.getCreatedAt()))
                .toList());
  }

  @Transactional(readOnly = true)
  public NoticeDetail detail(Long id) {
    Post p =
        noticeRepository
            .findNotice(AdminNoticeService.MAIN_SLUG, id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    return new NoticeDetail(
        p.getId(), p.getTitle(), renderer.render(p.getContent()), p.getCreatedAt());
  }
}
