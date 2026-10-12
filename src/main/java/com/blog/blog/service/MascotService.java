package com.blog.blog.service;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogTag;
import com.blog.blog.domain.Mascot;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.repository.BlogTagRepository;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 블로그 마스코트를 고른다 (T148, BLG-14, D-116). 블로그를 만들거나 이름·태그를 고치면 저장이 끝난 뒤 따로 고르고(블로그 저장은 기다리지 않음), 아직 없는
 * 블로그는 예약 작업이 조금씩 채운다. Claude가 못 고르면 태그 단어 사전으로 고른다.
 */
@Service
public class MascotService {

  /** 블로그 이름이나 태그가 바뀌었다. 커밋 뒤에 마스코트를 다시 고른다. */
  public record BlogInfoChanged(Long blogId) {}

  private static final Logger log = LoggerFactory.getLogger(MascotService.class);
  private static final int BACKFILL_BATCH = 20;

  private final BlogRepository blogRepository;
  private final BlogTagRepository blogTagRepository;
  private final ClaudeMascotClient claude;
  private final TransactionTemplate tx;
  private final boolean enabled;
  // Claude 호출이 몰리지 않게 한 번에 하나씩 고른다
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "mascot-picker");
            t.setDaemon(true);
            return t;
          });

  public MascotService(
      BlogRepository blogRepository,
      BlogTagRepository blogTagRepository,
      ClaudeMascotClient claude,
      PlatformTransactionManager transactionManager,
      @Value("${app.mascot.enabled:true}") boolean enabled) {
    this.blogRepository = blogRepository;
    this.blogTagRepository = blogTagRepository;
    this.claude = claude;
    this.tx = new TransactionTemplate(transactionManager);
    this.enabled = enabled;
  }

  @TransactionalEventListener
  public void onBlogInfoChanged(BlogInfoChanged event) {
    if (enabled) {
      worker.execute(() -> assignSafely(event.blogId()));
    }
  }

  /** 마스코트가 없는 블로그를 채운다. 기존 블로그와, 고르다 서버가 꺼진 블로그용. */
  @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT10M")
  @SchedulerLock(name = "mascotBackfill", lockAtMostFor = "PT9M")
  public void backfill() {
    if (!enabled) {
      return;
    }
    List<Long> ids = blogRepository.findIdsWithoutMascot(PageRequest.of(0, BACKFILL_BATCH));
    ids.forEach(this::assignSafely);
  }

  /**
   * 블로그 하나의 마스코트를 고르고 저장한다. Claude 호출은 트랜잭션 밖에서 한다.
   *
   * @return 고른 동물. 블로그가 없거나 폐쇄됐으면 null
   */
  public Mascot assign(Long blogId) {
    Info info =
        tx.execute(
            s ->
                blogRepository
                    .findById(blogId)
                    .filter(Blog::isOpen)
                    .map(b -> new Info(b.getName(), tagNames(b.getId())))
                    .orElse(null));
    if (info == null) {
      return null;
    }
    Mascot mascot =
        claude
            .choose(info.name(), info.tags())
            .orElseGet(() -> KeywordMascotPicker.pick(info.name(), info.tags()));
    tx.executeWithoutResult(
        s -> blogRepository.findById(blogId).ifPresent(b -> b.changeMascot(mascot)));
    return mascot;
  }

  private void assignSafely(Long blogId) {
    try {
      assign(blogId);
    } catch (RuntimeException e) {
      log.warn("블로그 {} 마스코트를 저장하지 못함: {}", blogId, e.toString());
    }
  }

  private List<String> tagNames(Long blogId) {
    return blogTagRepository.findWithTagByBlogIdIn(List.of(blogId)).stream()
        .map(BlogTag::getTag)
        .map(t -> t.getName())
        .toList();
  }

  @PreDestroy
  void shutdown() {
    worker.shutdownNow();
  }

  private record Info(String name, List<String> tags) {}
}
