package com.blog.blog.service;

import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogTag;
import com.blog.blog.domain.MascotDesign;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.repository.BlogTagRepository;
import jakarta.annotation.PreDestroy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * 블로그 마스코트를 만든다 (T148, BLG-14, D-116, D-118). 블로그를 만들거나 이름·태그를 고치면 저장이 끝난 뒤 따로 만들고(블로그 저장은 기다리지
 * 않음), 아직 없는 블로그는 예약 작업이 조금씩 채운다. Claude가 바탕 그림·색·모자·장식을 고르고, 못 고르면 태그 단어 사전으로 바탕을 고른 뒤 나머지는 이름과
 * 태그로 정한다. 다른 블로그나 이 블로그의 지금 그림과 같은 조합이면 다른 조합으로 바꾼다.
 */
@Service
public class MascotService {

  /** 블로그 이름이나 태그가 바뀌었다. 커밋 뒤에 마스코트를 다시 고른다. */
  public record BlogInfoChanged(Long blogId) {}

  private static final Logger log = LoggerFactory.getLogger(MascotService.class);
  private static final int BACKFILL_BATCH = 20;
  // 겹칠 때 다음 조합으로 건너뛰는 폭. 조합 수(288)와 서로소라 모든 조합을 한 번씩 돈다
  private static final int STEP = 115;

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
   * 블로그 하나의 마스코트를 만들고 저장한다. Claude 호출은 트랜잭션 밖에서 한다.
   *
   * @return 만든 조합. 블로그가 없거나 폐쇄됐으면 null
   */
  public MascotDesign assign(Long blogId) {
    Info info =
        tx.execute(
            s ->
                blogRepository
                    .findById(blogId)
                    .filter(Blog::isOpen)
                    .map(b -> new Info(b.getName(), tagNames(b.getId()), b.getMascot()))
                    .orElse(null));
    if (info == null) {
      return null;
    }
    MascotDesign wanted =
        claude.choose(info.name(), info.tags()).orElseGet(() -> fallback(info.name(), info.tags()));
    // 한 번에 하나씩 만들기 때문에(worker, ShedLock) 읽고 저장하는 사이에 같은 조합이 끼어들지 않는다
    MascotDesign mascot =
        tx.execute(
            s -> {
              Set<String> taken =
                  new HashSet<>(blogRepository.findMascotsInUse(wanted.base().key(), blogId));
              if (info.current() != null) {
                taken.add(info.current().value()); // 태그를 바꾸면 새 그림
              }
              MascotDesign design = unique(wanted, taken);
              blogRepository.findById(blogId).ifPresent(b -> b.changeMascot(design));
              return design;
            });
    return mascot;
  }

  private void assignSafely(Long blogId) {
    try {
      assign(blogId);
    } catch (RuntimeException e) {
      log.warn("블로그 {} 마스코트를 저장하지 못함: {}", blogId, e.toString());
    }
  }

  /** 단어 사전으로 바탕을 고르고 색·모자·장식은 이름과 태그로 정한다. 같은 입력이면 같은 조합. */
  static MascotDesign fallback(String name, List<String> tags) {
    int seed = (name == null ? "" : name).hashCode() * 31 + tags.hashCode();
    return MascotDesign.ofVariant(KeywordMascotPicker.pick(name, tags), seed);
  }

  /** 쓰고 있는 조합이면 같은 바탕 안에서 비어 있는 조합으로 바꾼다. 모두 차 있으면 원래 조합을 그대로 쓴다. */
  static MascotDesign unique(MascotDesign wanted, Set<String> taken) {
    for (int i = 0; i < MascotDesign.VARIANTS; i++) {
      MascotDesign d = MascotDesign.ofVariant(wanted.base(), wanted.variant() + i * STEP);
      if (!taken.contains(d.value())) {
        return d;
      }
    }
    return wanted;
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

  private record Info(String name, List<String> tags, MascotDesign current) {}
}
