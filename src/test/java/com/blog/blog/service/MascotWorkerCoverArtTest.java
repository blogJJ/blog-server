package com.blog.blog.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blog.blog.repository.BlogRepository;
import com.blog.blog.repository.BlogTagRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** 대표 그림은 블로그를 새로 만들 때만 마스코트 일꾼이 그린다. 이름·태그를 고칠 때는 다시 그리지 않는다 (T155, D-126). */
class MascotWorkerCoverArtTest {

  private final BlogRepository blogRepository = mock(BlogRepository.class);
  private final CoverArtService coverArt = mock(CoverArtService.class);
  private final MascotService service =
      new MascotService(
          blogRepository,
          mock(BlogTagRepository.class),
          mock(ClaudeMascotClient.class),
          coverArt,
          mock(PlatformTransactionManager.class),
          true);

  @Test
  void drawsOnlyForNewBlogs() {
    when(blogRepository.findById(1L)).thenReturn(Optional.empty());
    when(blogRepository.findById(2L)).thenReturn(Optional.empty());

    service.onBlogInfoChanged(new MascotService.BlogInfoChanged(2L, false));
    service.onBlogInfoChanged(new MascotService.BlogInfoChanged(1L, true));

    // 일꾼은 한 번에 하나씩 순서대로 돌므로 1번을 그렸으면 2번은 이미 지나갔다
    verify(coverArt, timeout(5000)).generateSafely(1L);
    verify(coverArt, never()).generateSafely(2L);
    service.shutdown();
  }
}
