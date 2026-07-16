package com.webshopx;

import com.google.gson.JsonObject;

record RelayRpcResponse(
    String id,
    boolean ok,
    int status,
    JsonObject payload,
    ErrorBody error) {
  static RelayRpcResponse ok(String id, int status, JsonObject payload) {
    return new RelayRpcResponse(id, true, status, payload, null);
  }

  static RelayRpcResponse error(String id, int status, String code, String message) {
    return new RelayRpcResponse(id, false, status, null, new ErrorBody(code, message));
  }

  record ErrorBody(String code, String message) {
  }
}
