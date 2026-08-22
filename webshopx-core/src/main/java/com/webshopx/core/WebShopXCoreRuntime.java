package com.webshopx.core;

import com.webshopx.platform.PlatformPorts;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Platform-neutral lifecycle gate. Writes are accepted only after all adapters report ready. */
public final class WebShopXCoreRuntime implements AutoCloseable {
  private final PlatformPorts.Bundle platform;
  private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

  public WebShopXCoreRuntime(PlatformPorts.Bundle platform) {
    this.platform = Objects.requireNonNull(platform, "platform");
  }

  public void start() {
    if (!state.compareAndSet(State.NEW, State.STARTING)) {
      throw new IllegalStateException("runtime already started: " + state.get());
    }
    platform.lifecycle().onStopping(this::close);
    platform.lifecycle().onReady(() -> state.compareAndSet(State.STARTING, State.READY));
  }

  public boolean acceptsWrites() {
    return state.get() == State.READY;
  }

  public State state() {
    return state.get();
  }

  public PlatformPorts.Bundle platform() {
    return platform;
  }

  @Override
  public void close() {
    State previous = state.getAndSet(State.STOPPING);
    if (previous != State.STOPPED) state.set(State.STOPPED);
  }

  public enum State { NEW, STARTING, READY, STOPPING, STOPPED }
}
