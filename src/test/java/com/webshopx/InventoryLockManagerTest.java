package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class InventoryLockManagerTest {
  @Test
  void serializesOperationsForTheSamePlayerAndCleansUp() {
    InventoryLockManager manager = new InventoryLockManager();
    UUID player = UUID.randomUUID();
    try (InventoryLockManager.Guard ignored =
             manager.acquire(player, Duration.ofMillis(100))) {
      assertTrue(manager.isLocked(player));
      CompletableFuture<Void> contender = CompletableFuture.runAsync(() ->
          assertThrows(
              ServiceException.class,
              () -> manager.acquire(player, Duration.ofMillis(25))));
      contender.join();
    }
    assertFalse(manager.isLocked(player));
  }

  @Test
  void differentPlayersDoNotBlockEachOther() {
    InventoryLockManager manager = new InventoryLockManager();
    UUID firstPlayer = UUID.randomUUID();
    UUID secondPlayer = UUID.randomUUID();
    try (InventoryLockManager.Guard first =
             manager.acquire(firstPlayer, Duration.ofMillis(100));
         InventoryLockManager.Guard second =
             manager.acquire(secondPlayer, Duration.ofMillis(100))) {
      assertTrue(manager.isLocked(firstPlayer));
      assertTrue(manager.isLocked(secondPlayer));
    }
  }
}
