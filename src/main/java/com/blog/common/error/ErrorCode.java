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
  // 가입·로그인 (US1)
  /** 가입된 이메일인지 드러나지 않게 이메일이 없을 때와 비밀번호가 틀렸을 때 같은 문구 (USR-03). */
  LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않아요."),
  /** 3번째 실패부터 Turnstile 사람 확인이 필요하다 (SEC-13). */
  CAPTCHA_REQUIRED(HttpStatus.BAD_REQUEST, "사람 확인을 해 주세요."),
  /** 5번 연속 실패로 5분 잠김 (SEC-03). */
  ACCOUNT_LOCKED(HttpStatus.LOCKED, "로그인을 5번 잘못해서 잠시 잠겼어요. 5분 뒤 다시 시도해 주세요."),
  /** 인증번호 재발송은 1분에 한 번 (USR-02). */
  CODE_RESEND_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, "인증번호는 1분에 한 번만 받을 수 있어요. 잠시 뒤 다시 시도해 주세요."),
  CODE_INVALID(HttpStatus.BAD_REQUEST, "인증번호가 맞지 않아요."),
  CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증번호가 만료되었어요. 인증번호를 다시 받아 주세요."),
  /** 가입 마지막 단계에서 서버가 인증 완료를 다시 확인했는데 없을 때 (USR-02). */
  EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증을 먼저 해 주세요."),
  DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일이에요."),
  DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 쓰고 있는 닉네임이에요."),
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
