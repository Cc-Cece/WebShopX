package com.webshopx;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Cross-runtime scheduling bridge for Paper and Folia.
 */
final class SchedulerBridge {
  private static final long MILLIS_PER_TICK = 50L;

  interface TaskHandle {
    void cancel();
  }

  private static final TaskHandle NOOP_TASK = () -> { };

  private final JavaPlugin plugin;
  private final FoliaBridge foliaBridge;

  private SchedulerBridge(JavaPlugin plugin, FoliaBridge foliaBridge) {
    this.plugin = plugin;
    this.foliaBridge = foliaBridge;
  }

  static SchedulerBridge create(JavaPlugin plugin) {
    return new SchedulerBridge(plugin, FoliaBridge.tryCreate());
  }

  boolean isFoliaRuntime() {
    return foliaBridge != null;
  }

  String runtimeName() {
    return isFoliaRuntime() ? "folia" : "paper";
  }

  TaskHandle runGlobal(Runnable runnable) {
    if (isFoliaRuntime()) {
      return foliaBridge.runGlobal(plugin, runnable);
    }
    return wrapBukkitTask(Bukkit.getScheduler().runTask(plugin, runnable));
  }

  TaskHandle runGlobalLater(Runnable runnable, long delayTicks) {
    long normalizedDelay = Math.max(1L, delayTicks);
    if (isFoliaRuntime()) {
      return foliaBridge.runGlobalLater(plugin, runnable, normalizedDelay);
    }
    return wrapBukkitTask(Bukkit.getScheduler().runTaskLater(plugin, runnable, normalizedDelay));
  }

  TaskHandle runGlobalTimer(Runnable runnable, long initialDelayTicks, long periodTicks) {
    long normalizedDelay = Math.max(1L, initialDelayTicks);
    long normalizedPeriod = Math.max(1L, periodTicks);
    if (isFoliaRuntime()) {
      return foliaBridge.runGlobalTimer(plugin, runnable, normalizedDelay, normalizedPeriod);
    }
    return wrapBukkitTask(
        Bukkit.getScheduler().runTaskTimer(plugin, runnable, normalizedDelay, normalizedPeriod));
  }

  TaskHandle runAsync(Runnable runnable) {
    if (isFoliaRuntime()) {
      return foliaBridge.runAsync(plugin, runnable);
    }
    return wrapBukkitTask(Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable));
  }

  TaskHandle runAsyncLater(Runnable runnable, long delayTicks) {
    long normalizedDelay = Math.max(1L, delayTicks);
    if (isFoliaRuntime()) {
      return foliaBridge.runAsyncLater(plugin, runnable, normalizedDelay);
    }
    return wrapBukkitTask(
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, normalizedDelay));
  }

  TaskHandle runAsyncTimer(Runnable runnable, long initialDelayTicks, long periodTicks) {
    long normalizedDelay = Math.max(1L, initialDelayTicks);
    long normalizedPeriod = Math.max(1L, periodTicks);
    if (isFoliaRuntime()) {
      return foliaBridge.runAsyncTimer(plugin, runnable, normalizedDelay, normalizedPeriod);
    }
    return wrapBukkitTask(Bukkit.getScheduler().runTaskTimerAsynchronously(
        plugin, runnable, normalizedDelay, normalizedPeriod));
  }

  CompletableFuture<Void> runGlobalFuture(Runnable runnable) {
    return supplyGlobal(() -> {
      runnable.run();
      return null;
    });
  }

  <T> CompletableFuture<T> supplyGlobal(Supplier<T> supplier) {
    CompletableFuture<T> future = new CompletableFuture<>();
    if (isOnGlobalExecutionThread()) {
      complete(future, supplier);
      return future;
    }
    runGlobal(() -> complete(future, supplier));
    return future;
  }

  private boolean isOnGlobalExecutionThread() {
    if (!isFoliaRuntime()) {
      return Bukkit.isPrimaryThread();
    }
    try {
      Method method = Bukkit.getServer().getClass().getMethod("isGlobalTickThread");
      Object result = method.invoke(Bukkit.getServer());
      return Boolean.TRUE.equals(result);
    } catch (ReflectiveOperationException ignored) {
      return false;
    }
  }

  CompletableFuture<Void> runLocationFuture(Location location, Runnable runnable) {
    return supplyLocation(location, () -> {
      runnable.run();
      return null;
    });
  }

  <T> CompletableFuture<T> supplyLocation(Location location, Supplier<T> supplier) {
    CompletableFuture<T> future = new CompletableFuture<>();
    if (location == null || location.getWorld() == null) {
      future.completeExceptionally(new IllegalArgumentException("location is missing world"));
      return future;
    }
    if (!isFoliaRuntime()) {
      return supplyGlobal(supplier);
    }
    if (foliaBridge.isOwnedByCurrentRegion(location)) {
      complete(future, supplier);
      return future;
    }
    foliaBridge.runRegion(plugin, location, () -> complete(future, supplier));
    return future;
  }

  <T> CompletableFuture<T> supplyPlayer(UUID playerUuid, Function<Player, T> function) {
    CompletableFuture<T> future = new CompletableFuture<>();
    if (playerUuid == null) {
      future.completeExceptionally(new IllegalArgumentException("player uuid is required"));
      return future;
    }
    if (!isFoliaRuntime()) {
      Runnable worker = () -> {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
          future.completeExceptionally(new IllegalStateException("player is offline"));
          return;
        }
        complete(future, () -> function.apply(player));
      };
      if (Bukkit.isPrimaryThread()) {
        worker.run();
      } else {
        runGlobal(worker);
      }
      return future;
    }

    Player immediatePlayer = Bukkit.getPlayer(playerUuid);
    if (immediatePlayer != null
        && immediatePlayer.isOnline()
        && foliaBridge.isOwnedByCurrentRegion(immediatePlayer)) {
      complete(future, () -> function.apply(immediatePlayer));
      return future;
    }

    // Resolve entity safely on the global scheduler first, then switch to entity scheduler.
    runGlobal(() -> {
      Player player = Bukkit.getPlayer(playerUuid);
      if (player == null || !player.isOnline()) {
        future.completeExceptionally(new IllegalStateException("player is offline"));
        return;
      }
      if (foliaBridge.isOwnedByCurrentRegion(player)) {
        complete(future, () -> function.apply(player));
        return;
      }
      foliaBridge.runEntity(
          plugin,
          player,
          () -> complete(future, () -> function.apply(player)),
          () -> future.completeExceptionally(new IllegalStateException("player is offline")));
    });
    return future;
  }

  void runPlayer(UUID playerUuid, Consumer<Player> consumer, Runnable offlineFallback) {
    supplyPlayer(playerUuid, player -> {
      consumer.accept(player);
      return null;
    }).whenComplete((ignored, throwable) -> {
      if (throwable == null || offlineFallback == null) {
        return;
      }
      Throwable root = unwrapCompletion(throwable);
      String message = root.getMessage() == null ? "" : root.getMessage().toLowerCase();
      if (root instanceof IllegalStateException && message.contains("offline")) {
        offlineFallback.run();
      } else {
        plugin.getLogger().warning("Failed to run player task: " + root.getMessage());
      }
    });
  }

  TaskHandle runPlayerLater(Player player, long delayTicks, Runnable runnable, Runnable retiredTask) {
    long normalizedDelay = Math.max(1L, delayTicks);
    if (player == null) {
      if (retiredTask != null) {
        retiredTask.run();
      }
      return NOOP_TASK;
    }
    if (!isFoliaRuntime()) {
      return wrapBukkitTask(Bukkit.getScheduler().runTaskLater(plugin, runnable, normalizedDelay));
    }
    return foliaBridge.runEntityLater(plugin, player, runnable, retiredTask, normalizedDelay);
  }

  private TaskHandle wrapBukkitTask(BukkitTask task) {
    if (task == null) {
      return NOOP_TASK;
    }
    return task::cancel;
  }

  private <T> void complete(CompletableFuture<T> future, Supplier<T> supplier) {
    try {
      future.complete(supplier.get());
    } catch (Throwable throwable) {
      future.completeExceptionally(throwable);
    }
  }

  private Throwable unwrapCompletion(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null
        && (current instanceof java.util.concurrent.CompletionException
        || current instanceof java.util.concurrent.ExecutionException)) {
      current = current.getCause();
    }
    return current;
  }

  private static final class FoliaBridge {
    private final Method globalExecute;
    private final Method globalRunDelayed;
    private final Method globalRunAtFixedRate;
    private final Method asyncRunNow;
    private final Method asyncRunDelayed;
    private final Method asyncRunAtFixedRate;
    private final Method regionExecute;
    private final Method entityGetScheduler;
    private final Method entityRun;
    private final Method entityRunDelayed;
    private final Method isOwnedByCurrentRegionLocation;
    private final Method isOwnedByCurrentRegionEntity;
    private final Method scheduledTaskCancel;
    private final Object globalScheduler;
    private final Object asyncScheduler;
    private final Object regionScheduler;

    private FoliaBridge(
        Method globalExecute,
        Method globalRunDelayed,
        Method globalRunAtFixedRate,
        Method asyncRunNow,
        Method asyncRunDelayed,
        Method asyncRunAtFixedRate,
        Method regionExecute,
        Method entityGetScheduler,
        Method entityRun,
        Method entityRunDelayed,
        Method isOwnedByCurrentRegionLocation,
        Method isOwnedByCurrentRegionEntity,
        Method scheduledTaskCancel,
        Object globalScheduler,
        Object asyncScheduler,
        Object regionScheduler) {
      this.globalExecute = globalExecute;
      this.globalRunDelayed = globalRunDelayed;
      this.globalRunAtFixedRate = globalRunAtFixedRate;
      this.asyncRunNow = asyncRunNow;
      this.asyncRunDelayed = asyncRunDelayed;
      this.asyncRunAtFixedRate = asyncRunAtFixedRate;
      this.regionExecute = regionExecute;
      this.entityGetScheduler = entityGetScheduler;
      this.entityRun = entityRun;
      this.entityRunDelayed = entityRunDelayed;
      this.isOwnedByCurrentRegionLocation = isOwnedByCurrentRegionLocation;
      this.isOwnedByCurrentRegionEntity = isOwnedByCurrentRegionEntity;
      this.scheduledTaskCancel = scheduledTaskCancel;
      this.globalScheduler = globalScheduler;
      this.asyncScheduler = asyncScheduler;
      this.regionScheduler = regionScheduler;
    }

    static FoliaBridge tryCreate() {
      try {
        Class<?> globalClass =
            Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
        Class<?> asyncClass =
            Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
        Class<?> regionClass =
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
        Class<?> entitySchedulerClass =
            Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
        Class<?> scheduledTaskClass =
            Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");

        Method getGlobal = Bukkit.class.getMethod("getGlobalRegionScheduler");
        Method getAsync = Bukkit.class.getMethod("getAsyncScheduler");
        Method getRegion = Bukkit.class.getMethod("getRegionScheduler");

        Object globalScheduler = getGlobal.invoke(null);
        Object asyncScheduler = getAsync.invoke(null);
        Object regionScheduler = getRegion.invoke(null);

        Method globalExecute = globalClass.getMethod("execute", Plugin.class, Runnable.class);
        Method globalRunDelayed =
            globalClass.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
        Method globalRunAtFixedRate =
            globalClass.getMethod(
                "runAtFixedRate",
                Plugin.class,
                Consumer.class,
                long.class,
                long.class);

        Method asyncRunNow = asyncClass.getMethod("runNow", Plugin.class, Consumer.class);
        Method asyncRunDelayed =
            asyncClass.getMethod(
                "runDelayed",
                Plugin.class,
                Consumer.class,
                long.class,
                TimeUnit.class);
        Method asyncRunAtFixedRate =
            asyncClass.getMethod(
                "runAtFixedRate",
                Plugin.class,
                Consumer.class,
                long.class,
                long.class,
                TimeUnit.class);

        Method regionExecute =
            regionClass.getMethod(
                "execute",
                Plugin.class,
                World.class,
                int.class,
                int.class,
                Runnable.class);

        Method entityGetScheduler = Entity.class.getMethod("getScheduler");
        Method entityRun =
            entitySchedulerClass.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
        Method entityRunDelayed =
            entitySchedulerClass.getMethod(
                "runDelayed",
                Plugin.class,
                Consumer.class,
                Runnable.class,
                long.class);

        Method isOwnedByCurrentRegionLocation =
            Bukkit.class.getMethod("isOwnedByCurrentRegion", Location.class);
        Method isOwnedByCurrentRegionEntity =
            Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class);

        Method scheduledTaskCancel = scheduledTaskClass.getMethod("cancel");

        return new FoliaBridge(
            globalExecute,
            globalRunDelayed,
            globalRunAtFixedRate,
            asyncRunNow,
            asyncRunDelayed,
            asyncRunAtFixedRate,
            regionExecute,
            entityGetScheduler,
            entityRun,
            entityRunDelayed,
            isOwnedByCurrentRegionLocation,
            isOwnedByCurrentRegionEntity,
            scheduledTaskCancel,
            globalScheduler,
            asyncScheduler,
            regionScheduler);
      } catch (Throwable ignored) {
        return null;
      }
    }

    TaskHandle runGlobal(JavaPlugin plugin, Runnable runnable) {
      invoke(globalExecute, globalScheduler, plugin, runnable);
      return NOOP_TASK;
    }

    TaskHandle runGlobalLater(JavaPlugin plugin, Runnable runnable, long delayTicks) {
      Object scheduledTask =
          invoke(globalRunDelayed, globalScheduler, plugin, toConsumer(runnable), delayTicks);
      return wrapFoliaTask(scheduledTask);
    }

    TaskHandle runGlobalTimer(
        JavaPlugin plugin, Runnable runnable, long initialDelayTicks, long periodTicks) {
      Object scheduledTask =
          invoke(
              globalRunAtFixedRate,
              globalScheduler,
              plugin,
              toConsumer(runnable),
              initialDelayTicks,
              periodTicks);
      return wrapFoliaTask(scheduledTask);
    }

    TaskHandle runAsync(JavaPlugin plugin, Runnable runnable) {
      Object scheduledTask = invoke(asyncRunNow, asyncScheduler, plugin, toConsumer(runnable));
      return wrapFoliaTask(scheduledTask);
    }

    TaskHandle runAsyncLater(JavaPlugin plugin, Runnable runnable, long delayTicks) {
      long delayMillis = ticksToMillis(delayTicks);
      Object scheduledTask =
          invoke(
              asyncRunDelayed,
              asyncScheduler,
              plugin,
              toConsumer(runnable),
              delayMillis,
              TimeUnit.MILLISECONDS);
      return wrapFoliaTask(scheduledTask);
    }

    TaskHandle runAsyncTimer(
        JavaPlugin plugin, Runnable runnable, long initialDelayTicks, long periodTicks) {
      long initialDelayMillis = ticksToMillis(initialDelayTicks);
      long periodMillis = ticksToMillis(periodTicks);
      Object scheduledTask =
          invoke(
              asyncRunAtFixedRate,
              asyncScheduler,
              plugin,
              toConsumer(runnable),
              initialDelayMillis,
              periodMillis,
              TimeUnit.MILLISECONDS);
      return wrapFoliaTask(scheduledTask);
    }

    void runRegion(JavaPlugin plugin, Location location, Runnable runnable) {
      int chunkX = location.getBlockX() >> 4;
      int chunkZ = location.getBlockZ() >> 4;
      invoke(regionExecute, regionScheduler, plugin, location.getWorld(), chunkX, chunkZ, runnable);
    }

    void runEntity(JavaPlugin plugin, Entity entity, Runnable runnable, Runnable retiredTask) {
      Object scheduler = invoke(entityGetScheduler, entity);
      invoke(entityRun, scheduler, plugin, toConsumer(runnable), retiredTask);
    }

    TaskHandle runEntityLater(
        JavaPlugin plugin, Entity entity, Runnable runnable, Runnable retiredTask, long delayTicks) {
      Object scheduler = invoke(entityGetScheduler, entity);
      Object scheduledTask =
          invoke(entityRunDelayed, scheduler, plugin, toConsumer(runnable), retiredTask, delayTicks);
      return wrapFoliaTask(scheduledTask);
    }

    boolean isOwnedByCurrentRegion(Location location) {
      return Boolean.TRUE.equals(invoke(isOwnedByCurrentRegionLocation, null, location));
    }

    boolean isOwnedByCurrentRegion(Entity entity) {
      return Boolean.TRUE.equals(invoke(isOwnedByCurrentRegionEntity, null, entity));
    }

    private TaskHandle wrapFoliaTask(Object scheduledTask) {
      if (scheduledTask == null) {
        return NOOP_TASK;
      }
      return () -> invoke(scheduledTaskCancel, scheduledTask);
    }

    private Consumer<Object> toConsumer(Runnable runnable) {
      return ignored -> runnable.run();
    }

    private long ticksToMillis(long ticks) {
      return Math.max(1L, ticks) * MILLIS_PER_TICK;
    }

    private Object invoke(Method method, Object target, Object... arguments) {
      try {
        return method.invoke(target, arguments);
      } catch (Exception exception) {
        throw new IllegalStateException("Failed to invoke Folia scheduler API", exception);
      }
    }
  }
}
