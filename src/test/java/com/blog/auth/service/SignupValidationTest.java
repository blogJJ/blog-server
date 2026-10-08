package com.blog.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blog.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 가입 입력 규칙 (T031, USR-01, SEC-02). */
class SignupValidationTest {

  @ParameterizedTest
  @ValueSource(strings = {"abcd123!", "Abcdefg1234567!", "a1~bcdef"})
  void acceptsPasswordsWithLetterDigitAndSymbol(String password) {
    SignupService.validatePassword(password);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"abc12!", "abcdefgh123456!x", "abcdefgh", "abcd1234", "abcd 123!", "1234567!"})
  void rejectsWeakOrOddPasswords(String password) {
    assertThatThrownBy(() -> SignupService.validatePassword(password))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void nicknameRulesAndReservedWords() {
    assertThat(SignupService.validateNickname(" 블로거12 ")).isEqualTo("블로거12");
    for (String bad :
        new String[] {"a", "열세글자가넘는닉네임입니다", "nick_name", "블로그관리자", "SuperAdmin", "탈퇴한회원1"}) {
      assertThatThrownBy(() -> SignupService.validateNickname(bad))
          .as(bad)
          .isInstanceOf(BusinessException.class);
    }
  }

  @Test
  void phoneIsNormalizedToDigits() {
    assertThat(SignupService.normalizePhone("010-1234 5678")).isEqualTo("01012345678");
    assertThat(SignupService.normalizePhone("0111234567")).isEqualTo("0111234567");
    assertThatThrownBy(() -> SignupService.normalizePhone("02-123-4567"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void emailIsLowercasedAndChecked() {
    assertThat(SignupService.normalizeEmail(" User@Example.COM ")).isEqualTo("user@example.com");
    assertThatThrownBy(() -> SignupService.normalizeEmail("not-an-email"))
        .isInstanceOf(BusinessException.class);
  }
}
