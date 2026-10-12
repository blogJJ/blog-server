package com.blog.admin.service;

import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import java.util.Set;

/** 관리자 조치의 사유·정지 기간 확인 */
final class AdminReasons {

  static final int REASON_MAX = 500;
  static final Set<Integer> SUSPEND_DAYS = Set.of(3, 14, 30);

  private AdminReasons() {}

  /** 사유는 1~500자 */
  static String valid(String raw) {
    String reason = raw == null ? "" : raw.strip();
    int length = reason.codePointCount(0, reason.length());
    if (length < 1 || length > REASON_MAX) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "사유를 1~500자로 입력해 주세요.");
    }
    return reason;
  }

  /** 정지 기간은 3·14·30일, 영구는 null */
  static Integer validDays(Integer days) {
    if (days != null && !SUSPEND_DAYS.contains(days)) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "정지 기간은 3일, 14일, 30일, 영구 중에서 골라 주세요.");
    }
    return days;
  }
}
