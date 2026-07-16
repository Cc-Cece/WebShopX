package com.webshopx;

import com.google.gson.JsonObject;
import java.util.Map;

record RelayRpcRequest(
    String id,
    String method,
    HttpEnvelope http,
    AuthEnvelope auth,
    JsonObject payload,
    String idempotencyKey,
    int timeoutMs) {
  record HttpEnvelope(String method, String path, Map<String, String> query, Map<String, String> headers) {
  }

  record AuthEnvelope(String token) {
  }
}
