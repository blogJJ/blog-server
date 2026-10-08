package com.blog.common.error;

import org.springframework.http.HttpStatus;

/**
 * API 오류 종류. 응답 본문의 code에 이름이 그대로 나간다. 기능별 오류는 여기에 더하고, 문구에는 내부 정보(SQL, 클래스 이름, 경로)를 넣지 않는다
 * (OPS-02, D-102).
 */
public enum ErrorCode {
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없어요."),
  /** 메인 관리자 계정 정지 중: 로그인과 읽기만 된다 (ADM-08). */
  ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "계정이 정지되어 있어 읽기만 할 수 있어요."),
  /** 메인 관리자 계정은 블로그 활동을 하지 않는다 (D-90, SEC-09). */
  ADMIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "관리자 계정은 블로그 활동을 할 수 없어요."),
  NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없어요."),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청이에요."),
  CONFLICT(HttpStatus.CONFLICT, "이미 처리되었거나 다른 요청과 겹쳤어요."),
  PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "파일이 너무 커요."),
  TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많아요. 잠시 뒤 다시 시도해 주세요."),
  /** 요청 처리가 5초를 넘음 (T009, D-48). */
  REQUEST_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "응답이 늦어지고 있어요. 잠시 뒤 다시 시도해 주세요."),
  /** 메일 발송 실패. 가입된 이메일인지와 상관없이 같은 문구 (T023, OPS-07, D-107). */
  MAIL_SEND_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "메일을 보내지 못했어요. 잠시 뒤 다시 시도해 주세요."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 생겼어요. 잠시 뒤 다시 시도해 주세요.");

  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(HttpStatus status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getDefaultMessage() {
    return defaultMessage;
  }
}
