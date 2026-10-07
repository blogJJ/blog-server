package com.blog.common.error;

/** 서비스에서 규칙 위반을 알릴 때 던진다. GlobalExceptionHandler가 {code, message}로 바꾼다. */
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;

  public BusinessException(ErrorCode errorCode) {
    this(errorCode, errorCode.getDefaultMessage());
  }

  /** message는 사용자에게 그대로 보이므로 내부 정보를 넣지 않는다. */
  public BusinessException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
