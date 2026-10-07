package com.blog.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 API 오류의 본문: {code, message}. 500일 때만 로그에서 찾을 오류 번호 errorId가 붙는다 (OPS-02, D-102).
 *
 * @param code ErrorCode 이름
 * @param message 사용자에게 보여 줄 문구
 * @param errorId 서버 오류 번호. 500이 아니면 응답에서 빠진다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String errorId) {

  public static ErrorResponse of(ErrorCode errorCode) {
    return new ErrorResponse(errorCode.name(), errorCode.getDefaultMessage(), null);
  }

  public static ErrorResponse of(ErrorCode errorCode, String message) {
    return new ErrorResponse(errorCode.name(), message, null);
  }
}
