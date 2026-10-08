package com.blog.common.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * 번호 페이지 응답. page는 1부터 센다.
 *
 * @param totalPages 결과가 없으면 0
 */
public record PageResponse<T>(List<T> items, int page, int totalPages, long totalElements) {

  public static <S, T> PageResponse<T> of(Page<S> page, Function<List<S>, List<T>> mapper) {
    return new PageResponse<>(
        mapper.apply(page.getContent()),
        page.getNumber() + 1,
        page.getTotalPages(),
        page.getTotalElements());
  }
}
