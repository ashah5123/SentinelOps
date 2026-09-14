package com.sentinelops.telemetry.web.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates every exception the API can throw into an RFC 9457 problem-detail response with a
 * stable, machine-readable error code. Never exposes a stack trace or internal database detail.
 * Mirrors the incident service's own {@code GlobalExceptionHandler}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final URI ERROR_TYPE_BASE = URI.create("https://sentinelops.dev/errors/");

  @ExceptionHandler(EvidenceNotFoundException.class)
  public ProblemDetail handleEvidenceNotFound(
      EvidenceNotFoundException e, HttpServletRequest request) {
    return build(HttpStatus.NOT_FOUND, ErrorCode.EVIDENCE_NOT_FOUND, e.getMessage(), request);
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

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(
      IllegalArgumentException e, HttpServletRequest request) {
    return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, e.getMessage(), request);
  }

  @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolation(
      jakarta.validation.ConstraintViolationException e, HttpServletRequest request) {
    return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, e.getMessage(), request);
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
