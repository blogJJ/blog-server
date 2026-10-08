package com.blog.blog.service;

import com.blog.auth.domain.User;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogJoinRequest;
import com.blog.blog.domain.BlogMember;
import com.blog.blog.domain.BlogMemberRole;
import com.blog.blog.domain.BlogStatus;
import com.blog.blog.domain.BlogTag;
import com.blog.blog.domain.BlogVisibility;
import com.blog.blog.domain.JoinPolicy;
import com.blog.blog.domain.JoinRequestStatus;
import com.blog.blog.domain.ManagerPermission;
import com.blog.blog.repository.BlogJoinRequestRepository;
import com.blog.blog.repository.BlogMemberRepository;
import com.blog.blog.repository.BlogRepository;
import com.blog.blog.repository.BlogSubscriptionRepository;
import com.blog.blog.repository.BlogTagRepository;
import com.blog.board.service.ImageService;
import com.blog.board.service.TagNormalizer;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.BlogAuthz;
import com.blog.common.web.PageResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 블로그 읽기: 목록·검색·첫 화면·내 블로그 (T048, T049, T052, BLG-02, BLG-03, BLG-06). */
@Service
public class BlogQueryService {

  public static final int PAGE_SIZE = 12;
  public static final int QUERY_MIN = 2;
  public static final int QUERY_MAX = 20;

  /** 목록 카드 하나 */
  public record BlogSummary(
      Long id,
      String slug,
      String name,
      String description,
      String coverImage,
      BlogVisibility visibility,
      BlogStatus status,
      int memberCount,
      int postCount,
      String ownerNickname,
      List<String> tags,
      LocalDateTime createdAt) {}

  /**
   * 블로그 첫 화면 (T055). viewer는 보는 사람 기준 상태.
   *
   * @param ownerSuspendedUntil 블로그장이 계정 정지 중이면 끝나는 시각. 화면이 "블로그장 정지 중" 안내를 띄운다 (ADM-08)
   * @param shareKey 정보 수정 권한이 있을 때만 준다
   */
  public record BlogDetail(
      Long id,
      String slug,
      String name,
      String description,
      String coverImage,
      BlogVisibility visibility,
      JoinPolicy joinPolicy,
      BlogStatus status,
      LocalDateTime closeScheduledAt,
      int memberCount,
      int postCount,
      int subscriberCount,
      String ownerNickname,
      LocalDateTime ownerSuspendedUntil,
      List<String> tags,
      LocalDateTime createdAt,
      String shareKey,
      Viewer viewer) {}

  /**
   * 보는 사람의 상태.
   *
   * @param role 멤버가 아니면 null
   * @param joinRequestId 대기 중인 신청 번호 (취소 버튼용)
   * @param reapplyAt 거절되어 다시 신청할 수 있는 시각. 지났거나 없으면 null
   * @param subscribed 이 블로그를 구독 중인지 (SOC-01). 일부 공개는 이 화면을 볼 수 있는 회원(링크로 온 회원·멤버)만 구독할 수 있다
   */
  public record Viewer(
      boolean loggedIn,
      BlogMemberRole role,
      JoinRequestStatus joinRequestStatus,
      Long joinRequestId,
      LocalDateTime reapplyAt,
      boolean canEditInfo,
      boolean canManageMembers,
      boolean canManagePosts,
      boolean owner,
      boolean subscribed) {}

  /** 내 블로그 목록 (BLG-06) */
  public record MyBlogs(
      List<BlogSummary> owned, List<MyJoined> joined, List<BlogSummary> pending) {}

  public record MyJoined(BlogSummary blog, BlogMemberRole role) {}

  /** 대기 신청 목록 한 줄 */
  public record JoinRequestRow(Long id, Long userId, String nickname, LocalDateTime createdAt) {}

  private final BlogRepository blogRepository;
  private final BlogTagRepository blogTagRepository;
  private final BlogMemberRepository memberRepository;
  private final BlogJoinRequestRepository requestRepository;
  private final BlogSubscriptionRepository subscriptionRepository;
  private final BlogAccessService accessService;
  private final BlogAuthz blogAuthz;
  private final Clock clock;

  public BlogQueryService(
      BlogRepository blogRepository,
      BlogTagRepository blogTagRepository,
      BlogMemberRepository memberRepository,
      BlogJoinRequestRepository requestRepository,
      BlogSubscriptionRepository subscriptionRepository,
      BlogAccessService accessService,
      BlogAuthz blogAuthz,
      Clock clock) {
    this.blogRepository = blogRepository;
    this.blogTagRepository = blogTagRepository;
    this.memberRepository = memberRepository;
    this.requestRepository = requestRepository;
    this.subscriptionRepository = subscriptionRepository;
    this.accessService = accessService;
    this.blogAuthz = blogAuthz;
    this.clock = clock;
  }

  /** 메인 목록 (T048). sort는 latest(기본) 또는 popular(멤버 수). page는 1부터. */
  @Transactional(readOnly = true)
  public PageResponse<BlogSummary> list(String sort, int page) {
    Sort order =
        "popular".equals(sort)
            ? Sort.by(Sort.Order.desc("memberCount"), Sort.Order.desc("id"))
            : Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    return PageResponse.of(
        blogRepository.findListed(PageRequest.of(pageIndex(page), PAGE_SIZE, order)),
        this::summaries);
  }

  /** 검색 (T049). 이름·소개는 단어마다 모두 들어 있는 블로그, 태그는 정리한 값과 같은 블로그. 관련도 순. */
  @Transactional(readOnly = true)
  public PageResponse<BlogSummary> search(String rawQuery, int page) {
    return search(rawQuery, "relevance", page);
  }

  /** 통합 검색의 블로그 분류 (T107, BRD-08). sort는 relevance(기본)·latest·popular. */
  @Transactional(readOnly = true)
  public PageResponse<BlogSummary> search(String rawQuery, String sort, int page) {
    String q = validateQuery(rawQuery);
    String fulltext = toBooleanQuery(q);
    String tag;
    try {
      tag = TagNormalizer.normalize(q);
    } catch (BusinessException e) {
      tag = null;
    }
    return PageResponse.of(
        blogRepository.search(
            fulltext,
            tag == null ? "" : tag,
            normalizeSort(sort),
            PageRequest.of(pageIndex(page), PAGE_SIZE)),
        this::summaries);
  }

  /** 검색어 규칙 (BRD-08): 앞뒤 공백을 뗀 2~20자, 글자나 숫자가 하나는 있어야 한다. 정리한 검색어를 돌려준다. */
  public static String validateQuery(String rawQuery) {
    String q = rawQuery == null ? "" : rawQuery.strip();
    if (q.length() < QUERY_MIN
        || q.length() > QUERY_MAX
        || q.replaceAll("[\\p{L}\\p{N}]", "").length() == q.length()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "검색어는 2~20자로, 글자나 숫자를 넣어 입력해 주세요.");
    }
    return q;
  }

  /** relevance·latest·popular 밖의 값은 relevance */
  public static String normalizeSort(String sort) {
    return "latest".equals(sort) || "popular".equals(sort) ? sort : "relevance";
  }

  /** 프로필에 보여 줄 블로그 (T103, SOC-03). 메인 목록에 나오는 공개 블로그만. 비공개·일부 공개는 참여 사실도 숨긴다. */
  @Transactional(readOnly = true)
  public ProfileBlogs profileBlogs(Long userId) {
    List<BlogMember> memberships =
        memberRepository.findWithBlogByUserId(userId).stream()
            .filter(
                m ->
                    m.getBlog().isOpen()
                        && !m.getBlog().isHidden()
                        && m.getBlog().getVisibility() == BlogVisibility.PUBLIC)
            .toList();
    Map<Long, BlogSummary> byId = new LinkedHashMap<>();
    for (BlogSummary s : summaries(memberships.stream().map(BlogMember::getBlog).toList())) {
      byId.put(s.id(), s);
    }
    List<BlogSummary> owned = new ArrayList<>();
    List<BlogSummary> joined = new ArrayList<>();
    for (BlogMember m : memberships) {
      (m.isOwner() ? owned : joined).add(byId.get(m.getBlog().getId()));
    }
    return new ProfileBlogs(owned, joined);
  }

  public record ProfileBlogs(List<BlogSummary> owned, List<BlogSummary> joined) {}

  /** 블로그 첫 화면 (T055). 볼 수 없으면 BlogAccessService가 막는다. */
  @Transactional(readOnly = true)
  public BlogDetail detail(String slug, Long viewerId, String shareKey) {
    BlogAccessService.Access access = accessService.checkBySlug(slug, viewerId, shareKey);
    Blog blog = access.blog();
    LocalDateTime now = LocalDateTime.now(clock);
    BlogMember member = access.membership().orElse(null);

    JoinRequestStatus requestStatus = null;
    Long requestId = null;
    LocalDateTime reapplyAt = null;
    if (viewerId != null && member == null) {
      BlogJoinRequest last =
          requestRepository
              .findFirstByBlogIdAndUserIdOrderByIdDesc(blog.getId(), viewerId)
              .orElse(null);
      if (last != null) {
        requestStatus = last.getStatus();
        if (last.isPending()) {
          requestId = last.getId();
        }
        if (last.getStatus() == JoinRequestStatus.REJECTED) {
          LocalDateTime at = last.getHandledAt().plus(JoinService.REAPPLY_AFTER_REJECT);
          reapplyAt = at.isAfter(now) ? at : null;
        }
      }
    }
    boolean canEdit =
        member != null && blogAuthz.hasPermission(blog.getId(), ManagerPermission.EDIT_INFO);
    boolean canManage =
        member != null && blogAuthz.hasPermission(blog.getId(), ManagerPermission.MANAGE_MEMBERS);
    boolean canManagePosts =
        member != null && blogAuthz.hasPermission(blog.getId(), ManagerPermission.MANAGE_POSTS);
    Viewer viewer =
        new Viewer(
            viewerId != null,
            member == null ? null : member.getRole(),
            requestStatus,
            requestId,
            reapplyAt,
            canEdit,
            canManage,
            canManagePosts,
            member != null && blogAuthz.isOwner(blog.getId()),
            viewerId != null
                && subscriptionRepository.existsByBlogIdAndUserId(blog.getId(), viewerId));

    User owner = blog.getOwner();
    return new BlogDetail(
        blog.getId(),
        blog.getSlug(),
        blog.getName(),
        blog.getDescription(),
        ImageService.url(blog.getCoverImage()),
        blog.getVisibility(),
        blog.getJoinPolicy(),
        blog.getStatus(),
        blog.getCloseScheduledAt(),
        blog.getMemberCount(),
        blog.getPostCount(),
        blog.getSubscriberCount(),
        owner.getNickname(),
        owner.isSuspendedAt(now) ? owner.getSuspendedUntil() : null,
        tagsOf(List.of(blog.getId())).getOrDefault(blog.getId(), List.of()),
        blog.getCreatedAt(),
        canEdit ? blog.getShareKey() : null,
        viewer);
  }

  /** 내 블로그 목록 (T052). 폐쇄된 블로그는 빼고, 만든 블로그·참여한 블로그·승인 대기로 나눈다. */
  @Transactional(readOnly = true)
  public MyBlogs myBlogs(Long userId) {
    List<BlogMember> memberships =
        memberRepository.findWithBlogByUserId(userId).stream()
            .filter(m -> m.getBlog().isOpen())
            .toList();
    List<Blog> pendingBlogs =
        requestRepository.findPendingWithBlogByUserId(userId).stream()
            .map(BlogJoinRequest::getBlog)
            .filter(Blog::isOpen)
            .toList();
    List<Blog> all = new ArrayList<>(memberships.stream().map(BlogMember::getBlog).toList());
    all.addAll(pendingBlogs);
    Map<Long, BlogSummary> byId = new LinkedHashMap<>();
    for (BlogSummary s : summaries(all)) {
      byId.put(s.id(), s);
    }
    List<BlogSummary> owned = new ArrayList<>();
    List<MyJoined> joined = new ArrayList<>();
    for (BlogMember m : memberships) {
      if (m.isOwner()) {
        owned.add(byId.get(m.getBlog().getId()));
      } else {
        joined.add(new MyJoined(byId.get(m.getBlog().getId()), m.getRole()));
      }
    }
    List<BlogSummary> pending = pendingBlogs.stream().map(b -> byId.get(b.getId())).toList();
    return new MyBlogs(owned, joined, pending);
  }

  /** 관리 화면의 대기 신청 목록 (T056). 권한은 컨트롤러가 확인한다. */
  @Transactional(readOnly = true)
  public List<JoinRequestRow> pendingRequests(Long blogId) {
    return requestRepository
        .findWithUserByBlogIdAndStatus(blogId, JoinRequestStatus.PENDING)
        .stream()
        .map(
            r ->
                new JoinRequestRow(
                    r.getId(), r.getUser().getId(), r.getUser().getNickname(), r.getCreatedAt()))
        .toList();
  }

  /**
   * 검색어를 FULLTEXT boolean 검색식으로 바꾼다. 연산자 문자는 지우고, 단어마다 {@code +"단어"}로 묶어 모두 들어 있는 블로그만 찾는다. ngram
   * 기본 길이(2)보다 짧은 단어는 뺀다.
   */
  static String toBooleanQuery(String q) {
    StringBuilder sb = new StringBuilder();
    for (String word : q.split("\\s+")) {
      String w = word.replaceAll("[^\\p{L}\\p{N}_]", "");
      if (w.length() >= 2) {
        if (!sb.isEmpty()) {
          sb.append(' ');
        }
        sb.append("+\"").append(w).append('"');
      }
    }
    return sb.toString();
  }

  private List<BlogSummary> summaries(List<Blog> blogs) {
    if (blogs.isEmpty()) {
      return List.of();
    }
    Map<Long, List<String>> tags = tagsOf(blogs.stream().map(Blog::getId).toList());
    return blogs.stream()
        .map(
            b ->
                new BlogSummary(
                    b.getId(),
                    b.getSlug(),
                    b.getName(),
                    b.getDescription(),
                    ImageService.url(b.getCoverImage()),
                    b.getVisibility(),
                    b.getStatus(),
                    b.getMemberCount(),
                    b.getPostCount(),
                    b.getOwner().getNickname(),
                    tags.getOrDefault(b.getId(), List.of()),
                    b.getCreatedAt()))
        .toList();
  }

  private Map<Long, List<String>> tagsOf(List<Long> blogIds) {
    Map<Long, List<String>> map = new LinkedHashMap<>();
    for (BlogTag bt : blogTagRepository.findWithTagByBlogIdIn(blogIds)) {
      map.computeIfAbsent(bt.getBlog().getId(), k -> new ArrayList<>()).add(bt.getTag().getName());
    }
    return map;
  }

  private static int pageIndex(int page) {
    return Math.max(page, 1) - 1;
  }
}
