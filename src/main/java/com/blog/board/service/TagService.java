package com.blog.board.service;

import com.blog.board.domain.Tag;
import com.blog.board.repository.TagRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 태그 행을 찾거나 만든다. 이름 정리는 {@link TagNormalizer}가 한다. 블로그와 글이 같이 쓴다. */
@Service
public class TagService {

  private final TagRepository tagRepository;

  public TagService(TagRepository tagRepository) {
    this.tagRepository = tagRepository;
  }

  /**
   * 정리한 이름들의 태그를 돌려준다. 없는 태그는 만든다.
   *
   * @param names {@link TagNormalizer#normalizeAll}을 거친 이름
   * @return names 순서대로
   */
  @Transactional
  public List<Tag> findOrCreate(List<String> names) {
    if (names.isEmpty()) {
      return List.of();
    }
    List<Tag> found = tagRepository.findByNameIn(names);
    if (found.size() < names.size()) {
      List<String> existing = found.stream().map(Tag::getName).toList();
      names.stream().filter(n -> !existing.contains(n)).forEach(tagRepository::insertIfAbsent);
      found = tagRepository.findByNameIn(names);
    }
    return found.stream().sorted(Comparator.comparingInt(t -> names.indexOf(t.getName()))).toList();
  }
}
