package com.blog.social.service;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.service.BlogQueryService;
import com.blog.blog.service.BlogQueryService.BlogSummary;
import com.blog.board.service.ImageService;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.social.repository.UserBlockRepository;
import com.blog.social.repository.UserFollowRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 프로필 (T103, SOC-03, 4.4). 닉네임, 사진, 소개, 운영하는 블로그, 참여한 블로그, 팔로워 수만 준다. 이메일·이름·전화번호는 없다. */
@Service
public class ProfileService {

  /**
   * @param ownedBlogs 운영하는 공개 블로그. 비공개·일부 공개는 빼서 참여 사실이 드러나지 않게 한다
   * @param viewer 보는 사람 기준 상태. 비회원이면 모두 false
   */
  public record Profile(
      Long id,
      String nickname,
      String profileImage,
      String bio,
      long followerCount,
      List<BlogSummary> ownedBlogs,
      List<BlogSummary> joinedBlogs,
      Viewer viewer) {}

  /**
   * @param me 내 프로필이면 true
   * @param blocking 내가 이 회원을 차단했는지
   * @param canFollow 팔로우 버튼을 누를 수 있는지. 내 프로필이거나 차단한 사이면 false
   */
  public record Viewer(boolean me, boolean following, boolean blocking, boolean canFollow) {}

  private final UserRepository userRepository;
  private final UserFollowRepository followRepository;
  private final UserBlockRepository blockRepository;
  private final BlogQueryService blogQueryService;

  public ProfileService(
      UserRepository userRepository,
      UserFollowRepository followRepository,
      UserBlockRepository blockRepository,
      BlogQueryService blogQueryService) {
    this.userRepository = userRepository;
    this.followRepository = followRepository;
    this.blockRepository = blockRepository;
    this.blogQueryService = blogQueryService;
  }

  @Transactional(readOnly = true)
  public Profile profile(Long userId, Long viewerId) {
    User user =
        userRepository
            .findById(userId)
            .filter(User::isActive)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    boolean me = user.getId().equals(viewerId);
    boolean following =
        viewerId != null && followRepository.existsByFollowerIdAndFolloweeId(viewerId, userId);
    boolean blocking =
        viewerId != null && !me && blockRepository.existsByBlockerIdAndBlockedId(viewerId, userId);
    boolean canFollow =
        viewerId != null && !me && (following || !blockRepository.existsBetween(viewerId, userId));
    BlogQueryService.ProfileBlogs blogs = blogQueryService.profileBlogs(userId);
    return new Profile(
        user.getId(),
        user.getNickname(),
        ImageService.url(user.getProfileImage()),
        user.getBio(),
        followRepository.countFollowers(userId),
        blogs.owned(),
        blogs.joined(),
        new Viewer(me, following, blocking, canFollow));
  }
}
