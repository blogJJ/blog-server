package com.blog.main.api;

import com.blog.main.service.ReleaseNoteService;
import com.blog.main.service.ReleaseNoteService.ReleaseDetail;
import com.blog.main.service.ReleaseNoteService.ReleaseSummary;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 릴리즈 노트 (T159, D-130). 비회원도 읽는다. 화면은 release-notes.html(목록), release-note.html(버전 하나) */
@RestController
public class ReleaseNoteController {

  private final ReleaseNoteService releaseNoteService;

  public ReleaseNoteController(ReleaseNoteService releaseNoteService) {
    this.releaseNoteService = releaseNoteService;
  }

  /** 새 버전부터 */
  @GetMapping("/api/release-notes")
  public List<ReleaseSummary> list() {
    return releaseNoteService.list();
  }

  @GetMapping("/api/release-notes/{version}")
  public ReleaseDetail detail(@PathVariable String version) {
    return releaseNoteService.detail(version);
  }
}
