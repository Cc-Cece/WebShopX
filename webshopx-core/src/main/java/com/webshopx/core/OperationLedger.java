package com.webshopx.core;

import com.webshopx.platform.PlatformResult;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Process-local first line of idempotency; durable adapters persist the same operation id. */
public final class OperationLedger {
  private final ConcurrentHashMap<String, PlatformResult<?>> completed = new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked")
  public <T> PlatformResult<T> once(String operationId, Supplier<PlatformResult<T>> operation) {
    Objects.requireNonNull(operationId, "operationId");
    if (operationId.isBlank()) throw new IllegalArgumentException("operationId must not be blank");
    return (PlatformResult<T>) completed.computeIfAbsent(operationId, ignored -> operation.get());
  }

  public int size() {
    return completed.size();
  }
}
