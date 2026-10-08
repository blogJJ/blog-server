package com.blog.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 4.4 표의 예시 그대로 가려지는지 (T019, D-22). */
class MaskingTest {

  @ParameterizedTest
  @CsvSource({
    "abcdef@gmail.com, ab***@gmail.com",
    "abc@naver.com, ab***@naver.com",
    "ab@naver.com, a***@naver.com",
    "a@naver.com, a***@naver.com"
  })
  void masksEmail(String email, String expected) {
    assertThat(Masking.email(email)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "01012345678, 010-****-5678",
    "010-1234-5678, 010-****-5678",
    "0111234567, 011-***-4567"
  })
  void masksPhone(String phone, String expected) {
    assertThat(Masking.phone(phone)).isEqualTo(expected);
  }

  @Test
  void keepsEmptyValuesAndHidesOddOnes() {
    assertThat(Masking.email(null)).isNull();
    assertThat(Masking.phone(null)).isNull();
    assertThat(Masking.email("not-an-email")).isEqualTo("***");
    assertThat(Masking.phone("12345")).isEqualTo("***");
  }
}
