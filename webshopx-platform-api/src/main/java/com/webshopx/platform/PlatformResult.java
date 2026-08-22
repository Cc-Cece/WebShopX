package com.webshopx.platform;

import java.time.Duration;

/** Closed result model used at every platform boundary; null never represents capability state. */
public sealed interface PlatformResult<T>
    permits PlatformResult.Success, PlatformResult.Rejected, PlatformResult.Unavailable,
        PlatformResult.Conflict, PlatformResult.UnknownOutcome {

  record Success<T>(T value) implements PlatformResult<T> { }

  record Rejected<T>(String errorCode, String messageKey, boolean retryable)
      implements PlatformResult<T> { }

  record Unavailable<T>(String capability, String reason, Duration retryAfter)
      implements PlatformResult<T> { }

  record Conflict<T>(String operationId, String currentState) implements PlatformResult<T> { }

  record UnknownOutcome<T>(String operationId, boolean reconciliationRequired)
      implements PlatformResult<T> { }

  static <T> Success<T> success(T value) {
    return new Success<>(value);
  }

  static <T> Rejected<T> rejected(String code, String messageKey) {
    return new Rejected<>(code, messageKey, false);
  }
}
