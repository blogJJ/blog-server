package com.blog.auth.api;

import com.blog.auth.domain.User;
import com.blog.auth.domain.UserRole;
import com.blog.auth.repository.UserRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.AuthUser;
import java.time.LocalDateTime;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 정보 (USR-07). 지금은 화면 상단 메뉴(layout.js)가 로그인 상태를 알 수 있게 읽기만 둔다. 수정(PUT)은 T098에서 더한다. 본인에게는
 * 이메일·전화번호를 가리지 않고 보여 준다.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

  private final UserRepository userRepository;

  public MeController(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * @param suspendedUntil 계정 정지 중이면 끝나는 시각, 아니면 null (ADM-08)
   */
  public record MeResponse(
      Long id,
      String email,
      String name,
      String nickname,
      String phone,
      UserRole role,
      LocalDateTime suspendedUntil) {}

  @GetMapping
  public MeResponse me(@AuthenticationPrincipal AuthUser authUser) {
    User user =
        userRepository
            .findById(authUser.id())
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    return new MeResponse(
        user.getId(),
        user.getEmail(),
        user.getName(),
        user.getNickname(),
        user.getPhone(),
        user.getRole(),
        user.getSuspendedUntil());
  }
}
