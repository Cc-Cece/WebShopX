package com.webshopx.platform;

public interface SchedulerGateway {
  void runSync(Runnable task);

  void runAsync(Runnable task);

  void runSyncLater(Runnable task, long delayTicks);
}
