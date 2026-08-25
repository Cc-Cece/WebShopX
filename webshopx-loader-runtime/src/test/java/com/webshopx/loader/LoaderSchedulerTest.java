package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.webshopx.platform.PlatformPorts;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LoaderSchedulerTest {
  @Test void rejectsNativeThreadWorkUntilServerExecutorIsBound() {
    try (LoaderScheduler scheduler = new LoaderScheduler()) {
      assertThrows(CompletionException.class, () -> scheduler.runGlobal(() -> { }).toCompletableFuture().join());
      assertFalse(scheduler.isOnRequiredThread(PlatformPorts.ThreadScope.GLOBAL, null));
    }
  }

  @Test void executesAndCancelsPeriodicAsyncWork() throws Exception {
    try (LoaderScheduler scheduler = new LoaderScheduler()) {
      CountDownLatch async = new CountDownLatch(1);
      scheduler.runAsync(() -> {
        assertTrue(scheduler.isOnRequiredThread(PlatformPorts.ThreadScope.ASYNC, null));
        async.countDown();
      }).toCompletableFuture().join();
      assertTrue(async.await(1, TimeUnit.SECONDS));

      CountDownLatch periodic = new CountDownLatch(2);
      PlatformPorts.ScheduledHandle handle = scheduler.schedule(
          Duration.ZERO, Duration.ofMillis(10), periodic::countDown);
      assertTrue(periodic.await(1, TimeUnit.SECONDS));
      handle.cancel();
    }
  }

  @Test void rejectsInvalidPeriodsAndWorkAfterClose() {
    LoaderScheduler scheduler = new LoaderScheduler();
    assertThrows(IllegalArgumentException.class,
        () -> scheduler.schedule(Duration.ZERO, Duration.ZERO, () -> { }));
    scheduler.close();
    assertThrows(CompletionException.class,
        () -> scheduler.runAsync(() -> { }).toCompletableFuture().join());
  }

  @Test void dispatchesGlobalAndPlayerWorkThroughBoundServerExecutor() {
    try (LoaderScheduler scheduler = new LoaderScheduler()) {
      Executor directServer = Runnable::run;
      scheduler.bind(directServer);
      scheduler.runGlobal(() -> assertTrue(scheduler.isOnRequiredThread(
          PlatformPorts.ThreadScope.GLOBAL, null))).toCompletableFuture().join();
      scheduler.runForPlayer(java.util.UUID.randomUUID(), () -> assertTrue(scheduler.isOnRequiredThread(
          PlatformPorts.ThreadScope.PLAYER, null))).toCompletableFuture().join();
      scheduler.unbind();
      assertThrows(CompletionException.class,
          () -> scheduler.runGlobal(() -> { }).toCompletableFuture().join());
    }
  }

  @Test void executesInlineWhenAlreadyOnTheBoundNativeServerThread() {
    try (LoaderScheduler scheduler = new LoaderScheduler()) {
      AtomicBoolean executorInvoked = new AtomicBoolean();
      scheduler.bind((Executor) ignored -> executorInvoked.set(true));
      AtomicBoolean actionInvoked = new AtomicBoolean();
      scheduler.runGlobal(() -> {
        actionInvoked.set(true);
        assertTrue(scheduler.isOnRequiredThread(PlatformPorts.ThreadScope.GLOBAL, null));
      }).toCompletableFuture().join();
      assertTrue(actionInvoked.get());
      assertFalse(executorInvoked.get());
    }
  }

  @Test void deferGlobalAlwaysUsesTheNativeQueue() {
    try (LoaderScheduler scheduler = new LoaderScheduler()) {
      AtomicReference<Runnable> queued = new AtomicReference<>();
      scheduler.bind((Executor) queued::set);
      AtomicBoolean actionInvoked = new AtomicBoolean();
      var completion = scheduler.deferGlobal(() -> actionInvoked.set(true));
      assertFalse(actionInvoked.get());
      assertFalse(completion.toCompletableFuture().isDone());
      queued.get().run();
      completion.toCompletableFuture().join();
      assertTrue(actionInvoked.get());
    }
  }
}
