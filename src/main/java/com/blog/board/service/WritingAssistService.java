package com.blog.board.service;

import com.blog.blog.domain.Blog;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.repository.BlogTagRepository;
import com.blog.board.repository.PostTagRepository;
import com.blog.board.service.ClaudeWritingClient.SpellingFix;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.ratelimit.RateLimiter;
import com.blog.common.security.AccountGuard;
import com.blog.common.security.AccountGuard.Activity;
import com.blog.common.security.BlogAuthz;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 글쓰기 화면 도우미: 태그 추천과 맞춤법 검사 (T167).
 *
 * <ul>
 *   <li>그 블로그에 글을 쓸 수 있는 회원만(글쓰기와 같은 조건: 계정 정지 아님, 운영 중인 블로그의 멤버).
 *   <li>회원별로 1분·하루 횟수를 센다. 넘으면 429와 안내 문구.
 *   <li>돌려주는 값은 후보뿐이다. 글과 태그는 글쓴이가 화면에서 고른 것만 바뀌고, 서버는 아무것도 저장하지 않는다.
 *   <li>태그는 Claude 키가 없거나 호출이 실패하면 단어 세기({@link KeywordTagSuggester})로 고른다. 맞춤법 검사는 키가 없으면 화면에서 버튼을
 *       숨기고, 실패하면 "잠시 뒤 다시" 안내.
 * </ul>
 *
 * <p>Claude 호출은 수십 초 걸릴 수 있어 트랜잭션 밖에서 부른다(요청 5초 제한 D-48은 트랜잭션·비동기 응답에 걸린다).
 */
@Service
public class WritingAssistService {

  public static final int TAG_COUNT = 5;

  /** 태그 추천에 보내는 본문 길이(글자). 앞부분이면 주제를 알기에 충분하다 */
  static final int TAG_TEXT_MAX = 3000;

  /** 마크다운을 벗기기 전에 자르는 길이. 큰 요청에 정규식을 오래 돌리지 않게 */
  private static final int RAW_MAX = 20_000;

  /** 맞춤법 검사 길이(글자). 글 본문 최대 길이와 같다 */
  static final int SPELLING_TEXT_MAX = 5000;

  /** 맞춤법 후보 한 개의 최대 길이. 이보다 길면 문장을 고쳐 쓴 것이라 버린다 */
  private static final int FIX_MAX = 60;

  private static final int MAX_FIXES = 30;
  private static final int KNOWN_TAGS = 40;
  private static final Duration MINUTE = Duration.ofMinutes(1);
  private static final Duration DAY = Duration.ofDays(1);

  public record TagSuggestions(List<String> tags, String source) {}

  /**
   * @param start 보낸 글 안의 시작 위치(UTF-16, 자바스크립트 문자열 위치와 같다)
   * @param end 끝 위치(포함하지 않음)
   */
  public record Fix(int start, int end, String original, String suggestion, String reason) {}

  /**
   * @param truncated 글이 길어 앞부분만 봤는지
   */
  public record SpellingResult(List<Fix> fixes, boolean truncated) {}

  public record Features(boolean tags, boolean spelling) {}

  public record Limits(
      int tagsPerMinute, int tagsPerDay, int spellingPerMinute, int spellingPerDay) {}

  private final ClaudeWritingClient client;
  private final BlogRepository blogRepository;
  private final BlogTagRepository blogTagRepository;
  private final PostTagRepository postTagRepository;
  private final BlogAuthz blogAuthz;
  private final AccountGuard accountGuard;
  private final RateLimiter rateLimiter;
  private final Limits limits;

  public WritingAssistService(
      ClaudeWritingClient client,
      BlogRepository blogRepository,
      BlogTagRepository blogTagRepository,
      PostTagRepository postTagRepository,
      BlogAuthz blogAuthz,
      AccountGuard accountGuard,
      RateLimiter rateLimiter,
      @Value("${app.writing-assist.tags-per-minute:5}") int tagsPerMinute,
      @Value("${app.writing-assist.tags-per-day:60}") int tagsPerDay,
      @Value("${app.writing-assist.spelling-per-minute:3}") int spellingPerMinute,
      @Value("${app.writing-assist.spelling-per-day:20}") int spellingPerDay) {
    this.client = client;
    this.blogRepository = blogRepository;
    this.blogTagRepository = blogTagRepository;
    this.postTagRepository = postTagRepository;
    this.blogAuthz = blogAuthz;
    this.accountGuard = accountGuard;
    this.rateLimiter = rateLimiter;
    this.limits = new Limits(tagsPerMinute, tagsPerDay, spellingPerMinute, spellingPerDay);
  }

  /** 화면이 버튼을 보일지 정한다. 태그 추천은 키가 없어도 단어 세기로 된다. */
  public Features features() {
    return new Features(true, client.enabled());
  }

  public TagSuggestions suggestTags(
      Long userId, Long blogId, String title, String content, List<String> currentTags) {
    checkWriter(userId, blogId);
    String safeTitle = AssistText.head(title == null ? "" : title.strip(), 100);
    String body =
        AssistText.head(AssistText.plain(AssistText.head(content, RAW_MAX)), TAG_TEXT_MAX);
    if (safeTitle.isEmpty() && body.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "제목이나 본문을 먼저 써 주세요.");
    }
    limit(userId, "assist-tags", limits.tagsPerMinute(), limits.tagsPerDay(), "추천 태그");

    Set<String> exclude =
        new LinkedHashSet<>(
            normalizeQuietly(currentTags == null ? null : currentTags.stream().limit(30).toList()));
    List<String> known = knownTags(blogId);

    if (client.enabled()) {
      Optional<List<String>> ai = client.suggestTags(safeTitle, body, known, List.copyOf(exclude));
      if (ai.isPresent()) {
        List<String> tags =
            normalizeQuietly(ai.get()).stream()
                .filter(t -> !exclude.contains(t))
                .limit(TAG_COUNT)
                .toList();
        if (!tags.isEmpty()) {
          return new TagSuggestions(tags, "ai");
        }
      }
    }
    return new TagSuggestions(
        KeywordTagSuggester.suggest(safeTitle, body, known, exclude, TAG_COUNT), "keyword");
  }

  public SpellingResult checkSpelling(Long userId, Long blogId, String content) {
    checkWriter(userId, blogId);
    if (!client.enabled()) {
      throw new BusinessException(ErrorCode.ASSIST_UNAVAILABLE);
    }
    if (content == null || content.isBlank()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "본문을 먼저 써 주세요.");
    }
    limit(userId, "assist-spelling", limits.spellingPerMinute(), limits.spellingPerDay(), "맞춤법 검사");
    String text = AssistText.head(content, SPELLING_TEXT_MAX);
    List<SpellingFix> raw =
        client
            .checkSpelling(text)
            .orElseThrow(() -> new BusinessException(ErrorCode.ASSIST_UNAVAILABLE));
    return new SpellingResult(locate(text, raw), text.length() < content.length());
  }

  /**
   * Claude가 준 후보를 글 안에서 찾아 위치를 붙인다. 글에 없는 것, 고칠 게 없는 것, 너무 긴 것, 앞의 후보와 겹치는 것은 버린다. 같은 말이 여러 번 나오면 앞
   * 후보 뒤에서부터 찾는다(후보가 나온 순서대로 오므로).
   */
  static List<Fix> locate(String text, List<SpellingFix> raw) {
    List<Fix> out = new ArrayList<>();
    List<int[]> taken = new ArrayList<>();
    int cursor = 0;
    for (SpellingFix f : raw.stream().filter(Objects::nonNull).toList()) {
      if (out.size() >= MAX_FIXES) {
        break;
      }
      int at = worthFixing(f) ? findAfterOrAnywhere(text, f.original(), cursor, taken) : -1;
      if (at >= 0) {
        int end = at + f.original().length();
        taken.add(new int[] {at, end});
        cursor = end;
        String reason = f.reason() == null ? "" : AssistText.head(f.reason().strip(), 120);
        out.add(new Fix(at, end, f.original(), f.suggestion(), reason));
      }
    }
    out.sort((a, b) -> Integer.compare(a.start(), b.start()));
    return out;
  }

  /** 비어 있지 않고, 실제로 바꾸며, 너무 길지 않은 후보인지 (null 후보는 부르는 쪽에서 이미 거름) */
  private static boolean worthFixing(SpellingFix f) {
    return f.original() != null
        && f.suggestion() != null
        && !f.original().isBlank()
        && !f.original().equals(f.suggestion())
        && f.original().length() <= FIX_MAX
        && f.suggestion().length() <= FIX_MAX * 2;
  }

  /** 앞 후보 뒤에서 먼저 찾고, 없으면 처음부터 찾는다 */
  private static int findAfterOrAnywhere(
      String text, String needle, int cursor, List<int[]> taken) {
    int at = find(text, needle, cursor, taken);
    return at >= 0 ? at : find(text, needle, 0, taken);
  }

  private static int find(String text, String needle, int from, List<int[]> taken) {
    for (int i = text.indexOf(needle, from); i >= 0; i = text.indexOf(needle, i + 1)) {
      int start = i;
      int end = i + needle.length();
      if (taken.stream().noneMatch(r -> start < r[1] && r[0] < end)) {
        return i;
      }
    }
    return -1;
  }

  private void checkWriter(Long userId, Long blogId) {
    if (blogId == null) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "블로그를 알 수 없어요.");
    }
    accountGuard.check(userId, Activity.WRITE_POST);
    blogRepository
        .findById(blogId)
        .filter(Blog::isOpen)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (!blogAuthz.isMember(blogId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "이 블로그 멤버만 쓸 수 있어요.");
    }
  }

  /** 회원별 1분·하루 횟수. 1분 제한에 걸린 요청은 하루 횟수에 세지 않는다. */
  private void limit(Long userId, String name, int perMinute, int perDay, String what) {
    if (!rateLimiter.tryAcquire(name + "-min:" + userId, perMinute, MINUTE).allowed()) {
      throw new BusinessException(
          ErrorCode.TOO_MANY_REQUESTS,
          what + "는 1분에 " + perMinute + "번까지 쓸 수 있어요. 잠시 뒤 다시 눌러 주세요.");
    }
    if (!rateLimiter.tryAcquire(name + "-day:" + userId, perDay, DAY).allowed()) {
      throw new BusinessException(
          ErrorCode.TOO_MANY_REQUESTS, what + "는 하루에 " + perDay + "번까지 쓸 수 있어요. 내일 다시 이용해 주세요.");
    }
  }

  /** 이 블로그의 태그와 이 블로그 글에 많이 쓴 태그 */
  private List<String> knownTags(Long blogId) {
    Set<String> names = new LinkedHashSet<>();
    blogTagRepository
        .findWithTagByBlogIdIn(List.of(blogId))
        .forEach(bt -> names.add(bt.getTag().getName()));
    names.addAll(postTagRepository.findTagNamesByBlogId(blogId, PageRequest.of(0, KNOWN_TAGS)));
    return names.stream().limit(KNOWN_TAGS).toList();
  }

  /** 규칙에 맞지 않는 태그는 오류 대신 버린다. 겹치면 하나로. */
  private static List<String> normalizeQuietly(List<String> raws) {
    if (raws == null) {
      return List.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String raw : raws) {
      try {
        String tag = TagNormalizer.normalize(raw);
        if (tag != null) {
          out.add(tag);
        }
      } catch (BusinessException ignored) {
        // 길거나 기호가 든 후보는 버린다
      }
    }
    return new ArrayList<>(out);
  }
}
