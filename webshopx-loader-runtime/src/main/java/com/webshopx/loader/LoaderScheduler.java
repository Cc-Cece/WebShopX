package com.webshopx.loader;

import com.webshopx.platform.PlatformPorts;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Async-only scheduler used while no native server executor has been bound. */
final class LoaderScheduler implements PlatformPorts.Scheduler, AutoCloseable {
  private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
  private final ScheduledExecutorService async = Executors.newScheduledThreadPool(2, runnable -> {
    Thread thread = new Thread(runnable, "webshopx-async-" + THREAD_SEQUENCE.incrementAndGet());
    thread.setDaemon(true);
    return thread;
  });
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ThreadLocal<Boolean> asyncThread = ThreadLocal.withInitial(() -> false);
  private final ThreadLocal<Boolean> serverThread = ThreadLocal.withInitial(() -> false);
  private volatile Executor serverExecutor;
  private volatile Object nativeServer;
  private volatile Thread nativeServerThread;

  @Override
  public CompletionStage<Void> runGlobal(Runnable action) {
    Executor current = serverExecutor;
    if (current == null) return unavailable("global server executor is not bound");
    CompletableFuture<Void> result = new CompletableFuture<>();
    if (Thread.currentThread() == nativeServerThread || serverThread.get()) {
      runServerAction(action, result);
      return result;
    }
    try {
      current.execute(() -> runServerAction(action, result));
    } catch (RuntimeException failure) {
      result.completeExceptionally(failure);
    }
    return result;
  }

  @Override
  public CompletionStage<Void> runForPlayer(UUID playerId, Runnable action) {
    return runGlobal(action);
  }

  /** Always appends work to the native server queue, even when called by the server thread. */
  CompletionStage<Void> deferGlobal(Runnable action) {
    Executor current = serverExecutor;
    if (current == null) return unavailable("global server executor is not bound");
    CompletableFuture<Void> result = new CompletableFuture<>();
    try {
      current.execute(() -> runServerAction(action, result));
    } catch (RuntimeException failure) {
      result.completeExceptionally(failure);
    }
    return result;
  }

  @Override
  public CompletionStage<Void> runAsync(Runnable action) {
    if (closed.get()) return unavailable("scheduler is stopped");
    CompletableFuture<Void> result = new CompletableFuture<>();
    async.execute(() -> runAsyncAction(action, result));
    return result;
  }

  @Override
  public PlatformPorts.ScheduledHandle schedule(Duration delay, Duration period, Runnable action) {
    if (delay.isNegative()) throw new IllegalArgumentException("delay must not be negative");
    if (period.isZero() || period.isNegative()) throw new IllegalArgumentException("period must be positive");
    if (closed.get()) throw new IllegalStateException("scheduler is stopped");
    ScheduledFuture<?> future = async.scheduleAtFixedRate(
        () -> runAsyncAction(action, new CompletableFuture<>()),
        delay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    return () -> future.cancel(false);
  }

  @Override
  public boolean isOnRequiredThread(PlatformPorts.ThreadScope scope, UUID playerId) {
    if (scope == PlatformPorts.ThreadScope.ASYNC) return asyncThread.get();
    return serverThread.get();
  }

  @Override
  public void close() {
    serverExecutor = null;
    nativeServer = null;
    nativeServerThread = null;
    if (closed.compareAndSet(false, true)) async.shutdownNow();
  }

  void bind(Object server) {
    if (!(server instanceof Executor executor)) {
      throw new IllegalArgumentException("native server does not implement Executor: " + server.getClass().getName());
    }
    serverExecutor = executor;
    nativeServer = server;
    nativeServerThread = Thread.currentThread();
  }

  void unbind() {
    serverExecutor = null;
    nativeServer = null;
    nativeServerThread = null;
  }

  Object nativeServer() {
    return nativeServer;
  }

  private void runAsyncAction(Runnable action, CompletableFuture<Void> result) {
    asyncThread.set(true);
    try {
      action.run();
      result.complete(null);
    } catch (Throwable failure) {
      result.completeExceptionally(failure);
    } finally {
      asyncThread.remove();
    }
  }

  private void runServerAction(Runnable action, CompletableFuture<Void> result) {
    serverThread.set(true);
    try {
      action.run();
      result.complete(null);
    } catch (Throwable failure) {
      result.completeExceptionally(failure);
    } finally {
      serverThread.remove();
    }
  }

  private static CompletionStage<Void> unavailable(String message) {
    return CompletableFuture.failedFuture(new IllegalStateException(message));
  }
}
