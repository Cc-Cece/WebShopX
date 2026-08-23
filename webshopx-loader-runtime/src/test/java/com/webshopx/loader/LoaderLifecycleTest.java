package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoaderLifecycleTest {
  @Test void firesEachPhaseOnceAndSupportsLateListeners() {
    LoaderLifecycle lifecycle = new LoaderLifecycle();
    AtomicInteger ready = new AtomicInteger();
    AtomicInteger stopping = new AtomicInteger();
    lifecycle.onReady(ready::incrementAndGet);
    lifecycle.onStopping(stopping::incrementAndGet);
    lifecycle.fireReady();
    lifecycle.fireReady();
    lifecycle.onReady(ready::incrementAndGet);
    lifecycle.fireStopping();
    lifecycle.fireStopping();
    lifecycle.onStopping(stopping::incrementAndGet);
    assertEquals(2, ready.get());
    assertEquals(2, stopping.get());
  }
}
