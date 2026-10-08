package com.blog.common.error;

import com.blog.common.logging.RequestIdFilter;
import com.blog.common.logging.SecurityEventLogger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 컨트롤러의 예외를 한곳에서 {code, message} 응답으로 바꾼다 (T008, OPS-02, D-102).
 *
 * <p>스택 트레이스·SQL·클래스 이름·서버 경로는 응답에 넣지 않는다. 예상하지 못한 오류(500)는 로그에만 자세히 남기고, 응답에는 로그에서 찾을 오류
 * 번호(errorId)만 준다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  /** 요청 ID 필터(T137)가 MDC에 넣는 키. 없으면 오류 번호를 새로 만든다. */
  static final String REQUEST_ID_KEY = RequestIdFilter.MDC_KEY;

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
    return respond(e.getErrorCode(), e.getMessage());
  }

  /**
   * @Valid 요청 본문·폼 검증 실패. 첫 번째 항목의 문구를 보여 준다.
   */
  @ExceptionHandler(BindException.class)
  public ResponseEntity<ErrorResponse> handleBind(BindException e) {
    FieldError fieldError = e.getBindingResult().getFieldError();
    String message =
        fieldError != null && fieldError.getDefaultMessage() != null
            ? fieldError.getDefaultMessage()
            : ErrorCode.INVALID_INPUT.getDefaultMessage();
    return respond(ErrorCode.INVALID_INPUT, message);
  }

  /** 메서드 파라미터 검증(@RequestParam @Size 등) 실패. */
  @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
  public ResponseEntity<ErrorResponse> handleMethodValidation(Exception e) {
    return respond(ErrorCode.INVALID_INPUT);
  }

  /** JSON 형식 오류, 지원하지 않는 Content-Type, 필수 파라미터 누락, 타입이 맞지 않는 값. 원인 문구는 내부 정보가 섞여 있어 보여 주지 않는다. */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    HttpMediaTypeNotSupportedException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
    return respond(ErrorCode.INVALID_INPUT);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException e) {
    return respond(ErrorCode.PAYLOAD_TOO_LARGE);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
    return respond(ErrorCode.NOT_FOUND);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException e) {
    return respond(ErrorCode.METHOD_NOT_ALLOWED);
  }

  /**
   * @PreAuthorize 거부. 컨트롤러 안에서 난 것은 Security 필터까지 가지 않으므로 여기서 받는다.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(
      AccessDeniedException e, HttpServletRequest request) {
    SecurityEventLogger.accessDenied(request);
    return respond(ErrorCode.FORBIDDEN);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException e) {
    return respond(ErrorCode.UNAUTHORIZED);
  }

  /** 그 밖의 모든 예외는 500. 로그에는 스택 트레이스와 오류 번호를, 응답에는 오류 번호만 남긴다. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
    String errorId = currentErrorId();
    log.error("Unhandled exception [errorId={}]", errorId, e);
    ErrorCode code = ErrorCode.INTERNAL_ERROR;
    return ResponseEntity.status(code.getStatus())
        .body(new ErrorResponse(code.name(), code.getDefaultMessage(), errorId));
  }

  private static ResponseEntity<ErrorResponse> respond(ErrorCode code) {
    return respond(code, code.getDefaultMessage());
  }

  private static ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
    return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code, message));
  }

  private static String currentErrorId() {
    String requestId = MDC.get(REQUEST_ID_KEY);
    return requestId != null ? requestId : UUID.randomUUID().toString().substring(0, 8);
  }
}
