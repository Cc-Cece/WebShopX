package com.webshopx;

import com.webshopx.platform.SchedulerGateway;

final class BukkitSchedulerGateway implements SchedulerGateway {

  private final SchedulerBridge schedulerBridge;

  BukkitSchedulerGateway(SchedulerBridge schedulerBridge) {
    this.schedulerBridge = schedulerBridge;
  }

  @Override
  public void runSync(Runnable task) {
    schedulerBridge.runGlobal(task);
  }

  @Override
  public void runAsync(Runnable task) {
    schedulerBridge.runAsync(task);
  }

  @Override
  public void runSyncLater(Runnable task, long delayTicks) {
    schedulerBridge.runGlobalLater(task, delayTicks);
  }
}
