package com.webshopx.loader;

import com.webshopx.SharedCommerceService;
import com.webshopx.core.ItemEnvelopeBinaryCodec;
import com.webshopx.platform.InventoryTypes.InventoryMutation;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformPorts;
import com.webshopx.platform.PlatformResult;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Connects durable delivery rows to native inventory and command adapters. */
final class NativeDeliveryCoordinator {
  private final SharedCommerceService commerce;
  private final PlatformPorts.InventoryGateway inventories;
  private final NativeItemCodec items;
  private final LoaderScheduler scheduler;
  private final String serverId;
  private final Set<UUID> running = ConcurrentHashMap.newKeySet();

  NativeDeliveryCoordinator(SharedCommerceService commerce, PlatformPorts.InventoryGateway inventories,
      NativeItemCodec items, LoaderScheduler scheduler, String serverId) {
    this.commerce = commerce;
    this.inventories = inventories;
    this.items = items;
    this.scheduler = scheduler;
    this.serverId = serverId;
  }

  void deliverPending(UUID playerId) {
    if (!running.add(playerId)) return;
    scheduler.runAsync(() -> {
      try {
        for (SharedCommerceService.Delivery delivery
            : commerce.pendingDeliveries(playerId, serverId)) {
          deliver(playerId, delivery);
        }
      } finally {
        running.remove(playerId);
      }
    });
  }

  private void deliver(UUID playerId, SharedCommerceService.Delivery delivery) {
    if (!commerce.claimDelivery(delivery.id(), serverId)) return;
    try {
      if (delivery.kind() == SharedCommerceService.ProductKind.COMMAND) {
        executeCommand(delivery.command(), playerId);
        commerce.markDelivered(delivery.id(), delivery.quantity());
        return;
      }
      ItemEnvelope snapshotTemplate = snapshotEnvelope(delivery.payloadJson(), delivery.quantity());
      PlatformResult<ItemEnvelope> created = snapshotTemplate == null
          ? items.createEnvelope(registryId(delivery.payloadJson()), delivery.quantity())
          : PlatformResult.success(snapshotTemplate);
      if (!(created instanceof PlatformResult.Success<ItemEnvelope> success)) {
        commerce.markDeliveryRetry(delivery.id(), resultCode(created));
        return;
      }
      PlatformResult<com.webshopx.platform.InventoryTypes.InventorySnapshot> snapshot =
          inventories.snapshot(playerId, false).toCompletableFuture().join();
      if (!(snapshot instanceof PlatformResult.Success<com.webshopx.platform.InventoryTypes.InventorySnapshot>
          current)) {
        commerce.markDeliveryRetry(delivery.id(), resultCode(snapshot));
        return;
      }
      InventoryMutation mutation = new InventoryMutation(
          "delivery:" + delivery.id(), playerId, current.value().version(),
          List.of(success.value()), List.of());
      PlatformResult<com.webshopx.platform.InventoryTypes.InventoryMutationResult> applied =
          inventories.compareAndApply(mutation).toCompletableFuture().join();
      if (applied instanceof PlatformResult.Success<com.webshopx.platform.InventoryTypes.InventoryMutationResult>
          result && result.value().remainder().isEmpty()) {
        commerce.markDelivered(delivery.id(), delivery.quantity());
      } else if (applied instanceof PlatformResult.UnknownOutcome<?>) {
        commerce.markDeliveryUnknown(delivery.id(), resultCode(applied));
      } else {
        commerce.markDeliveryRetry(delivery.id(), resultCode(applied));
      }
    } catch (RuntimeException failure) {
      commerce.markDeliveryUnknown(delivery.id(), failure.getClass().getSimpleName());
    }
  }

  private void executeCommand(String template, UUID playerId) {
    if (template == null || template.isBlank()) {
      throw new IllegalArgumentException("empty delivery command");
    }
    String command = template.replace("{uuid}", playerId.toString());
    Object server = scheduler.nativeServer();
    try {
      Object commands = NativeItemCodec.method(server.getClass(),
          new String[]{"getCommands", "method_3733", "m_129892_"}, 0).invoke(server);
      Object source = NativeItemCodec.method(server.getClass(),
          new String[]{"createCommandSourceStack", "method_3813", "m_129893_"}, 0).invoke(server);
      Method perform = java.util.Arrays.stream(commands.getClass().getMethods())
          .filter(value -> value.getParameterCount() == 2)
          .filter(value -> value.getParameterTypes()[0].isInstance(source))
          .filter(value -> value.getParameterTypes()[1] == String.class)
          .filter(value -> value.getName().equals("performPrefixedCommand")
              || value.getName().equals("performCommand") || value.getName().equals("method_13530"))
          .findFirst().orElseThrow(() -> new NoSuchMethodException("command execution method"));
      perform.invoke(commands, source, command);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("native command delivery failed", failure);
    }
  }

  private static String registryId(String payload) {
    if (payload == null) throw new IllegalArgumentException("missing item payload");
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("\\\"registryId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(payload);
    if (!matcher.find()) throw new IllegalArgumentException("missing registryId");
    return matcher.group(1);
  }

  private static ItemEnvelope snapshotEnvelope(String payload, int quantity) {
    if (payload == null) return null;
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("\\\"envelopeBase64\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(payload);
    if (!matcher.find()) return null;
    ItemEnvelope template = new ItemEnvelopeBinaryCodec().decode(
        Base64.getDecoder().decode(matcher.group(1)));
    return new ItemEnvelope(
        template.schemaVersion(),
        template.codec(),
        template.codecVersion(),
        template.compatibilityDomain(),
        template.registryId(),
        quantity,
        template.payloadEncoding(),
        template.payload(),
        template.payloadHash(),
        template.summary(),
        template.createdAt());
  }

  private static String resultCode(PlatformResult<?> result) {
    if (result instanceof PlatformResult.Rejected<?> rejected) return rejected.errorCode();
    if (result instanceof PlatformResult.Unavailable<?> unavailable) return unavailable.capability();
    if (result instanceof PlatformResult.Conflict<?> conflict) return "conflict:" + conflict.currentState();
    if (result instanceof PlatformResult.UnknownOutcome<?> unknown) return "unknown:" + unknown.operationId();
    return result.getClass().getSimpleName();
  }
}
