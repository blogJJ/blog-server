package com.blog.social.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** spec.md 3.6 알림 종류 표와 맞는지 (T026). */
class NotificationTypeTest {

  @Test
  void requiredTypesMatchSpecTable() {
    assertThat(Arrays.stream(NotificationType.values()).filter(t -> !t.isOptional()))
        .containsExactlyInAnyOrder(
            NotificationType.OWNER_TRANSFER_REQUEST,
            NotificationType.OWNER_TRANSFER_RESULT,
            NotificationType.BLOG_CLOSING,
            NotificationType.BLOG_CLOSE_CANCELED,
            NotificationType.BLOG_PRIVATE,
            NotificationType.BLACKLIST_INQUIRY_RESULT,
            NotificationType.POST_DELETED,
            NotificationType.MEMBER_SANCTION,
            NotificationType.ACCOUNT_SANCTION,
            NotificationType.OWNER_SANCTION);
  }

  @Test
  void namesFitTypeColumn() {
    for (NotificationType type : NotificationType.values()) {
      assertThat(type.name().length()).as(type.name()).isLessThanOrEqualTo(40);
    }
  }
}
