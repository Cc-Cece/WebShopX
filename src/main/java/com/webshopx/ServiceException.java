package com.webshopx;

/**
 * Runtime exception used for predictable business-rule violations.
 */
public class ServiceException extends RuntimeException {
  private final String code;

  public ServiceException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
