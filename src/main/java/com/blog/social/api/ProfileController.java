package com.blog.social.api;

import com.blog.common.security.AuthUser;
import com.blog.social.service.ProfileService;
import com.blog.social.service.ProfileService.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 프로필 (T103, SOC-03). 비회원도 본다. */
@RestController
public class ProfileController {

  private final ProfileService profileService;

  public ProfileController(ProfileService profileService) {
    this.profileService = profileService;
  }

  @GetMapping("/api/users/{id}/profile")
  public Profile profile(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
    return profileService.profile(id, user == null ? null : user.id());
  }
}
