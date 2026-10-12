package com.blog.main.service;

import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.main.domain.ReleaseNote;
import com.blog.main.repository.ReleaseNoteRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/** 릴리즈 노트 읽기 (T159, D-130). 누구나 읽는다. */
@Service
public class ReleaseNoteService {

  /** 목록 한 줄: {@code v1.0.3 — 제목, 제목} 과 날짜 */
  public record ReleaseSummary(String version, String title, LocalDate date) {}

  public record ReleaseDetail(String version, String title, LocalDate date, List<String> changes) {}

  private final ReleaseNoteRepository repository;

  public ReleaseNoteService(ReleaseNoteRepository repository) {
    this.repository = repository;
  }

  /** 새 버전부터 */
  public List<ReleaseSummary> list() {
    return repository.findAll().stream()
        .map(n -> new ReleaseSummary(n.version(), n.title(), n.date()))
        .toList();
  }

  /** {@code 1.0.3}처럼 v 없이 와도 찾는다 */
  public ReleaseDetail detail(String version) {
    String key = version.startsWith("v") ? version : "v" + version;
    ReleaseNote n =
        repository.findByVersion(key).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    return new ReleaseDetail(n.version(), n.title(), n.date(), n.changes());
  }
}
