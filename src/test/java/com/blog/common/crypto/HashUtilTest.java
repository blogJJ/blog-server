package com.blog.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HashUtilTest {

  private final HashUtil hashUtil =
      new HashUtil(new HashProperties("test-secret-for-hmac-hashing-0123456789abcdef"));

  @Test
  void sameInputGivesSameHashSoItCanBeLookedUp() {
    assertThat(hashUtil.hash("123456")).isEqualTo(hashUtil.hash("123456")).hasSize(64);
    assertThat(hashUtil.hash("123456")).isNotEqualTo(hashUtil.hash("123457"));
  }

  @Test
  void differentSecretGivesDifferentHash() {
    HashUtil other = new HashUtil(new HashProperties("another-secret-for-hmac-0123456789abcdef"));
    assertThat(other.hash("123456")).isNotEqualTo(hashUtil.hash("123456"));
  }

  @Test
  void matchesComparesWithStoredHash() {
    String stored = hashUtil.hash("token-value");
    assertThat(hashUtil.matches("token-value", stored)).isTrue();
    assertThat(hashUtil.matches("token-valuX", stored)).isFalse();
    assertThat(hashUtil.matches(null, stored)).isFalse();
  }

  @Test
  void normalizesEmailAndPhoneBeforeHashing() {
    assertThat(hashUtil.hashEmail(" Kim@Example.com "))
        .isEqualTo(hashUtil.hashEmail("kim@example.com"));
    assertThat(hashUtil.hashPhone("010-1234-5678")).isEqualTo(hashUtil.hashPhone("01012345678"));
  }

  @Test
  void refusesShortSecret() {
    assertThatThrownBy(() -> new HashUtil(new HashProperties("short")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HASH_SECRET");
  }
}
