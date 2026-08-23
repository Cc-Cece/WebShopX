package com.webshopx.loader;

import com.webshopx.platform.PlatformPorts;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe lifecycle source used until a native Loader lifecycle adapter is attached. */
final class LoaderLifecycle implements PlatformPorts.Lifecycle {
  private final List<Runnable> ready = new CopyOnWriteArrayList<>();
  private final List<Runnable> stopping = new CopyOnWriteArrayList<>();
  private final AtomicBoolean readyFired = new AtomicBoolean();
  private final AtomicBoolean stoppingFired = new AtomicBoolean();

  @Override
  public void onReady(Runnable listener) {
    if (readyFired.get()) {
      listener.run();
    } else {
      ready.add(listener);
      if (readyFired.get() && ready.remove(listener)) listener.run();
    }
  }

  @Override
  public void onStopping(Runnable listener) {
    if (stoppingFired.get()) {
      listener.run();
    } else {
      stopping.add(listener);
      if (stoppingFired.get() && stopping.remove(listener)) listener.run();
    }
  }

  void fireReady() {
    if (!readyFired.compareAndSet(false, true)) return;
    ready.forEach(LoaderLifecycle::runSafely);
    ready.clear();
  }

  void fireStopping() {
    if (!stoppingFired.compareAndSet(false, true)) return;
    stopping.forEach(LoaderLifecycle::runSafely);
    stopping.clear();
  }

  private static void runSafely(Runnable listener) {
    try {
      listener.run();
    } catch (RuntimeException failure) {
      System.err.printf("[WebShopX] lifecycle listener failed: %s%n", failure);
    }
  }
}
