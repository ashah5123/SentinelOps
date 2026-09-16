package com.sentinelops.incident.web.error;

import com.sentinelops.incident.application.DeadLetterReplayService.IneligibleDeadLetterTopicException;
import com.sentinelops.incident.application.IdempotencyConflictException;
import com.sentinelops.incident.application.IncidentNotFoundException;
import com.sentinelops.incident.domain.IllegalIncidentTransitionException;
import com.sentinelops.incident.security.RestAccessDeniedHandler;
import com.sentinelops.incident.security.RestAuthenticationEntryPoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates every exception the API can throw into an RFC 9457 problem-detail response with a
 * stable, machine-readable error code. Never exposes a stack trace or internal database detail —
 * {@link #handleUncaught} is the deliberate last-resort catch-all that logs the full exception
 * server-side and returns only a generic message to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final URI ERROR_TYPE_BASE = URI.create("https://sentinelops.dev/errors/");

  private final RestAccessDeniedHandler accessDeniedHandler;
  private final RestAuthenticationEntryPoint authenticationEntryPoint;

  public GlobalExceptionHandler(
      RestAccessDeniedHandler accessDeniedHandler,
      RestAuthenticationEntryPoint authenticationEntryPoint) {
    this.accessDeniedHandler = accessDeniedHandler;
    this.authenticationEntryPoint = authenticationEntryPoint;
  }

  /**
   * A {@code @PreAuthorize} denial thrown from inside a controller/service method is caught here
   * (by Spring MVC's normal exception-handling path) rather than by Spring Security's
   * ExceptionTranslationFilter, since it occurs after the DispatcherServlet has already taken over.
   * Delegating to the same handler used at the filter level keeps the response body and the audit
   * trail identical regardless of where the denial originated.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public void handleAccessDenied(
      AccessDeniedException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    accessDeniedHandler.handle(request, response, e);
  }

  @ExceptionHandler(AuthenticationException.class)
  public void handleAuthenticationException(
      AuthenticationException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    authenticationEntryPoint.commence(request, response, e);
  }

  @ExceptionHandler(IncidentNotFoundException.class)
  public ProblemDetail handleNotFound(IncidentNotFoundException e, HttpServletRequest request) {
    return build(HttpStatus.NOT_FOUND, ErrorCode.INCIDENT_NOT_FOUND, e.getMessage(), request);
  }

  @ExceptionHandler(IllegalIncidentTransitionException.class)
  public ProblemDetail handleIllegalTransition(
      IllegalIncidentTransitionException e, HttpServletRequest request) {
    ProblemDetail problem =
        build(HttpStatus.CONFLICT, ErrorCode.ILLEGAL_TRANSITION, e.getMessage(), request);
    problem.setProperty("from", e.from().name());
    problem.setProperty("to", e.to().name());
    return problem;
  }

  @ExceptionHandler(IneligibleDeadLetterTopicException.class)
  public ProblemDetail handleIneligibleDeadLetterTopic(
      IneligibleDeadLetterTopicException e, HttpServletRequest request) {
    return build(
        HttpStatus.BAD_REQUEST, ErrorCode.DEAD_LETTER_TOPIC_NOT_ELIGIBLE, e.getMessage(), request);
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  public ProblemDetail handleIdempotencyConflict(
      IdempotencyConflictException e, HttpServletRequest request) {
    return build(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_KEY_CONFLICT, e.getMessage(), request);
  }

  @ExceptionHandler(MissingIdempotencyKeyException.class)
  public ProblemDetail handleMissingIdempotencyKey(
      MissingIdempotencyKeyException e, HttpServletRequest request) {
    return build(
        HttpStatus.BAD_REQUEST, ErrorCode.IDEMPOTENCY_KEY_MISSING, e.getMessage(), request);
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ProblemDetail handleOptimisticLock(
      OptimisticLockingFailureException e, HttpServletRequest request) {
    return build(
        HttpStatus.CONFLICT,
        ErrorCode.ILLEGAL_TRANSITION,
        "The incident was modified concurrently; retry with the latest version",
        request);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ProblemDetail handleTypeMismatch(
      MethodArgumentTypeMismatchException e, HttpServletRequest request) {
    return build(
        HttpStatus.BAD_REQUEST,
        ErrorCode.MALFORMED_REQUEST,
        "Malformed value for parameter '%s'".formatted(e.getName()),
        request);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ProblemDetail handleTooLarge(
      MaxUploadSizeExceededException e, HttpServletRequest request) {
    return build(
        HttpStatus.PAYLOAD_TOO_LARGE,
        ErrorCode.REQUEST_TOO_LARGE,
        "Request body too large",
        request);
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUncaught(Exception e, HttpServletRequest request) {
    log.error(
        "Unhandled exception processing {} {}", request.getMethod(), request.getRequestURI(), e);
    return build(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ErrorCode.INTERNAL_ERROR,
        "An unexpected error occurred",
        request);
  }

  @Override
  protected org.springframework.http.ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      org.springframework.http.HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    problem.setType(ERROR_TYPE_BASE.resolve(ErrorCode.VALIDATION_ERROR));
    problem.setTitle("Validation Error");
    problem.setProperty("errorCode", ErrorCode.VALIDATION_ERROR);
    problem.setProperty("timestamp", Instant.now());
    problem.setProperty(
        "fieldErrors",
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ValidationFieldError(fe.getField(), message(fe)))
            .toList());
    return org.springframework.http.ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
  }

  @Override
  protected org.springframework.http.ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      org.springframework.http.HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        build(
            HttpStatus.BAD_REQUEST,
            ErrorCode.MALFORMED_REQUEST,
            "Request body could not be parsed",
            null);
    return org.springframework.http.ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
  }

  @Override
  protected org.springframework.http.ResponseEntity<Object> handleHttpRequestMethodNotSupported(
      HttpRequestMethodNotSupportedException ex,
      HttpHeaders headers,
      org.springframework.http.HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.MALFORMED_REQUEST, ex.getMessage(), null);
    return org.springframework.http.ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(problem);
  }

  private ProblemDetail build(
      HttpStatus status, String errorCode, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(ERROR_TYPE_BASE.resolve(errorCode));
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("errorCode", errorCode);
    problem.setProperty("timestamp", Instant.now());
    if (request != null) {
      problem.setInstance(URI.create(request.getRequestURI()));
    }
    return problem;
  }

  private String message(FieldError fieldError) {
    return fieldError.getDefaultMessage() != null
        ? fieldError.getDefaultMessage()
        : "invalid value";
  }

  /** A single field-level validation failure. */
  public record ValidationFieldError(String field, String message) {}
}
