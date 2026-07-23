package com.webshopx;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

final class InventoryLockManager {
  private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

  Guard acquire(UUID playerUuid, Duration timeout) {
    ReentrantLock lock = locks.computeIfAbsent(playerUuid, ignored -> new ReentrantLock(true));
    try {
      if (!lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new ServiceException("inventory_locked", "Player inventory is busy; retry shortly");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ServiceException("inventory_locked", "Interrupted while waiting for player inventory");
    }
    return new Guard(playerUuid, lock);
  }

  boolean isLocked(UUID playerUuid) {
    ReentrantLock lock = locks.get(playerUuid);
    return lock != null && lock.isLocked();
  }

  final class Guard implements AutoCloseable {
    private final UUID playerUuid;
    private final ReentrantLock lock;
    private boolean closed;

    private Guard(UUID playerUuid, ReentrantLock lock) {
      this.playerUuid = playerUuid;
      this.lock = lock;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      lock.unlock();
      if (!lock.isLocked() && !lock.hasQueuedThreads()) {
        locks.remove(playerUuid, lock);
      }
    }
  }
}
