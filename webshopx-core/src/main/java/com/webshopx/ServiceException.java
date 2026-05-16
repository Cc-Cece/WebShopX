package com.webshopx;

/**
 * Runtime exception used for predictable business-rule violations.
 */
class ServiceException extends RuntimeException {
  private final String code;

  ServiceException(String code, String message) {
    super(message);
    this.code = code;
  }

  String code() {
    return code;
  }
}
