package com.blog.auth.domain;

import com.blog.common.domain.BaseTimeEntity;
import com.blog.common.domain.Suspensions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Locale;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 회원 (users). 값 검사(비밀번호 규칙, 닉네임 예약어 등)는 가입 서비스(T032)에서 한다. */
@Entity
@Table(name = "users")
public class User extends BaseTimeEntity {

  public static final int MAX_LOGIN_FAILS = 5;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 소문자로 저장. 탈퇴 즉시 NULL이 되어 같은 이메일로 바로 다시 가입할 수 있다. */
  @Column(length = 100, unique = true)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  /** 바꿀 수 없음. 탈퇴 30일 뒤 NULL. */
  @Column(length = 30, updatable = false)
  private String name;

  @Column(length = 12, unique = true)
  private String nickname;

  /** 숫자만. 1차는 중복 허용. */
  @Column(length = 11)
  private String phone;

  @Column(name = "profile_image", length = 255)
  private String profileImage;

  @Column(length = 200)
  private String bio;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private UserRole role = UserRole.USER;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private UserStatus status = UserStatus.ACTIVE;

  /** 메인 관리자 계정 정지 끝나는 시각 (ADM-08). 영구 정지는 9999-12-31. */
  @Column(name = "suspended_until")
  private LocalDateTime suspendedUntil;

  @Column(name = "login_fail_count", nullable = false)
  private int loginFailCount;

  @Column(name = "locked_until")
  private LocalDateTime lockedUntil;

  /** 알림 보관 일수 30 또는 7 (SOC-04). */
  @JdbcTypeCode(SqlTypes.TINYINT)
  @Column(name = "notification_keep_days", nullable = false)
  private int notificationKeepDays = 30;

  @Column(name = "terms_agreed_at", nullable = false)
  private LocalDateTime termsAgreedAt;

  @Column(name = "privacy_agreed_at", nullable = false)
  private LocalDateTime privacyAgreedAt;

  @Column(name = "withdrawn_at")
  private LocalDateTime withdrawnAt;

  protected User() {}

  /** 가입 완료 때 만든다. 약관·개인정보 동의 시각은 가입 시각으로 같다 (USR-01). */
  public User(
      String email,
      String passwordHash,
      String name,
      String nickname,
      String phone,
      LocalDateTime agreedAt) {
    this.email = normalizeEmail(email);
    this.passwordHash = passwordHash;
    this.name = name;
    this.nickname = nickname;
    this.phone = phone;
    this.termsAgreedAt = agreedAt;
    this.privacyAgreedAt = agreedAt;
  }

  /** 이메일은 대소문자를 구분하지 않으므로 소문자로 맞춘다. 조회할 때도 이 함수를 쓴다. */
  public static String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }

  public boolean isActive() {
    return status == UserStatus.ACTIVE;
  }

  public boolean isAdmin() {
    return role == UserRole.ADMIN;
  }

  public boolean isSuspendedAt(LocalDateTime now) {
    return Suspensions.isSuspended(suspendedUntil, now);
  }

  public boolean isLockedAt(LocalDateTime now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  /** 이만큼 연속으로 틀리면 다음 로그인부터 사람 확인(Turnstile)을 요구한다 (SEC-13). */
  public static final int CAPTCHA_AFTER_FAILS = 3;

  /**
   * 비밀번호를 틀렸을 때. 연속 5번째부터는 5분 잠근다 (SEC-03). 잠금이 풀린 뒤에도 연속 실패는 이어서 세므로, 다시 틀리면 바로 잠긴다.
   *
   * @return 이번에 잠갔으면 true
   */
  public boolean recordLoginFailure(LocalDateTime now) {
    loginFailCount++;
    if (loginFailCount >= MAX_LOGIN_FAILS) {
      lockedUntil = now.plusMinutes(5);
      return true;
    }
    return false;
  }

  /** 로그인에 성공하면 실패 횟수와 잠금을 지운다. */
  public void resetLoginFailures() {
    loginFailCount = 0;
    lockedUntil = null;
  }

  /** 비밀번호 변경·재설정. 재설정이면 로그인 잠금도 함께 푼다 (USR-06) */
  public void changePassword(String passwordHash) {
    this.passwordHash = passwordHash;
    resetLoginFailures();
  }

  /** 내 정보 수정 (USR-07). 이메일과 이름은 바꿀 수 없다 (D-21, D-32) */
  public void changeProfile(String nickname, String phone, String bio) {
    this.nickname = nickname;
    this.phone = phone;
    this.bio = bio;
  }

  /**
   * 프로필 사진을 바꾸거나(null이면) 지운다.
   *
   * @return 이전 사진의 저장 이름. 없으면 null
   */
  public String changeProfileImage(String storedName) {
    String old = this.profileImage;
    this.profileImage = storedName;
    return old;
  }

  /** 알림 보관 일수 30 또는 7 (SOC-04) */
  public void changeNotificationKeepDays(int days) {
    if (days != 30 && days != 7) {
      throw new IllegalArgumentException("notification keep days must be 30 or 7: " + days);
    }
    this.notificationKeepDays = days;
  }

  /** 다음 로그인 때 사람 확인이 필요한지 */
  public boolean needsCaptcha() {
    return loginFailCount >= CAPTCHA_AFTER_FAILS;
  }

  public Long getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getName() {
    return name;
  }

  public String getNickname() {
    return nickname;
  }

  public String getPhone() {
    return phone;
  }

  public String getProfileImage() {
    return profileImage;
  }

  public String getBio() {
    return bio;
  }

  public UserRole getRole() {
    return role;
  }

  public UserStatus getStatus() {
    return status;
  }

  public LocalDateTime getSuspendedUntil() {
    return suspendedUntil;
  }

  public int getLoginFailCount() {
    return loginFailCount;
  }

  public LocalDateTime getLockedUntil() {
    return lockedUntil;
  }

  public int getNotificationKeepDays() {
    return notificationKeepDays;
  }

  public LocalDateTime getTermsAgreedAt() {
    return termsAgreedAt;
  }

  public LocalDateTime getPrivacyAgreedAt() {
    return privacyAgreedAt;
  }

  public LocalDateTime getWithdrawnAt() {
    return withdrawnAt;
  }
}
