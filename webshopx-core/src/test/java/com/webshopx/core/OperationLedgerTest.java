package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.webshopx.platform.PlatformResult;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OperationLedgerTest {
  @Test
  void executesAnOperationIdOnce() {
    OperationLedger ledger = new OperationLedger();
    AtomicInteger calls = new AtomicInteger();
    PlatformResult<Integer> first = ledger.once("op-1", () -> PlatformResult.success(calls.incrementAndGet()));
    PlatformResult<Integer> second = ledger.once("op-1", () -> PlatformResult.success(calls.incrementAndGet()));
    assertEquals(first, second);
    assertEquals(1, calls.get());
  }
}
