package com.blog.admin.service;

import com.blog.admin.domain.AdminAction;
import com.blog.admin.domain.AdminActionLog;
import com.blog.admin.domain.AdminTargetType;
import com.blog.admin.repository.AdminActionLogRepository;
import com.blog.auth.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 활동 기록 공통 처리 (T116, ADM-06, SEC-09). 관리자 조치 서비스는 조치를 마친 뒤 같은 트랜잭션에서 이것을 부른다. 조치가 실패해 되돌려지면 기록도
 * 남지 않는다. 사유는 500자를 넘으면 자른다.
 */
@Component
public class AdminActionLogger {

  private final AdminActionLogRepository logRepository;
  private final UserRepository userRepository;

  public AdminActionLogger(AdminActionLogRepository logRepository, UserRepository userRepository) {
    this.logRepository = logRepository;
    this.userRepository = userRepository;
  }

  /**
   * @param reason 사유. 없으면 null
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AdminActionLog log(
      Long adminId, AdminAction action, AdminTargetType targetType, Long targetId, String reason) {
    return logRepository.save(
        new AdminActionLog(
            userRepository.getReferenceById(adminId), action, targetType, targetId, cut(reason)));
  }

  private static String cut(String reason) {
    if (reason == null || reason.isBlank()) {
      return null;
    }
    String r = reason.strip();
    return r.length() <= AdminActionLog.REASON_MAX ? r : r.substring(0, AdminActionLog.REASON_MAX);
  }
}
