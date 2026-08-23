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
}
