package com.webshopx.loader;

import com.webshopx.platform.InventoryTypes.InventoryMutation;
import com.webshopx.platform.InventoryTypes.InventoryMutationResult;
import com.webshopx.platform.InventoryTypes.InventoryRemoval;
import com.webshopx.platform.InventoryTypes.InventorySnapshot;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Destructive, disposable-player acceptance probe for the native inventory gateway. */
final class NativeInventoryProbe {
  enum Mode { ONLINE, OFFLINE, RECOVERY, FIXTURE }

  private NativeInventoryProbe() { }

  static String run(
      NativeInventoryGateway inventories,
      NativeItemCodec items,
      UUID playerId,
      Mode mode,
      Path evidenceDirectory) {
    ProbeResult result;
    try {
      result = switch (mode) {
        case ONLINE -> online(inventories, items, playerId);
        case OFFLINE -> offline(inventories, items, playerId);
        case RECOVERY -> recovery(inventories, playerId);
        case FIXTURE -> fixture(inventories, items, playerId);
      };
    } catch (RuntimeException failure) {
      writeFailure(evidenceDirectory, mode.name().toLowerCase(), playerId, failure);
      throw failure;
    }
    writeEvidence(evidenceDirectory, result);
    return "WebShopX inventory-" + result.mode() + "=PASS uuid=" + playerId
        + " version=" + Long.toUnsignedString(result.version())
        + " freeSlots=" + result.freeSlots() + " items=" + result.itemCount()
        + " assertions=" + result.assertions();
  }

  private static ProbeResult online(
      NativeInventoryGateway inventories, NativeItemCodec items, UUID playerId) {
    InventorySnapshot before = success(inventories.snapshot(playerId, false), "online_snapshot");
    ItemEnvelope inserted = success(items.createEnvelope("minecraft:emerald", 13), "online_item");
    String insertId = "probe:online:insert:" + playerId;
    InventoryMutation insert = new InventoryMutation(
        insertId, playerId, before.version(), List.of(inserted), List.of());
    InventoryMutationResult applied = success(inventories.compareAndApply(insert), "online_insert");
    require(applied.inserted().size() == 1 && applied.remainder().isEmpty(), "online_insert_result");
    require(applied.equals(success(inventories.compareAndApply(insert), "online_insert_duplicate")),
        "online_insert_idempotency");
    require(conflict(inventories.compareAndApply(new InventoryMutation(
        "probe:online:stale:" + playerId, playerId, before.version(), List.of(), List.of()))),
        "online_stale_conflict");

    InventorySnapshot afterInsert = success(inventories.snapshot(playerId, false), "online_post_insert");
    ItemEnvelope observed = item(afterInsert, "minecraft:emerald", 13, "online_insert_visible");
    String removalId = "probe:online:remove:" + playerId;
    InventoryMutation removal = new InventoryMutation(
        removalId, playerId, afterInsert.version(), List.of(),
        List.of(new InventoryRemoval(observed, 12)));
    InventoryMutationResult removed = success(inventories.compareAndApply(removal), "online_remove");
    require(removed.removed().size() == 1 && removed.removed().get(0).count() == 12,
        "online_partial_remove_result");
    require(removed.equals(success(inventories.compareAndApply(removal), "online_remove_duplicate")),
        "online_remove_idempotency");
    InventorySnapshot after = success(inventories.snapshot(playerId, false), "online_final_snapshot");
    item(after, "minecraft:emerald", 1, "online_partial_remove_visible");
    require(after.freeSlots() == before.freeSlots() - 1, "online_free_slot_accounting");
    return new ProbeResult("online", playerId, after.version(), after.freeSlots(),
        after.items().size(), 9);
  }

  private static ProbeResult offline(
      NativeInventoryGateway inventories, NativeItemCodec items, UUID playerId) {
    InventorySnapshot before = success(inventories.snapshot(playerId, true), "offline_snapshot");
    item(before, "minecraft:emerald", 1, "online_change_persisted_to_playerdata");
    ItemEnvelope inserted = success(items.createEnvelope("minecraft:gold_ingot", 5), "offline_item");
    String insertId = "probe:offline:insert:" + playerId;
    InventoryMutation insert = new InventoryMutation(
        insertId, playerId, before.version(), List.of(inserted), List.of());
    InventoryMutationResult applied = success(inventories.compareAndApply(insert), "offline_insert");
    require(applied.inserted().size() == 1 && applied.remainder().isEmpty(), "offline_insert_result");
    require(applied.equals(success(inventories.compareAndApply(insert), "offline_insert_duplicate")),
        "offline_insert_idempotency");
    require(conflict(inventories.compareAndApply(new InventoryMutation(
        "probe:offline:stale:" + playerId, playerId, before.version(), List.of(), List.of()))),
        "offline_stale_conflict");

    InventorySnapshot afterInsert = success(inventories.snapshot(playerId, true), "offline_post_insert");
    ItemEnvelope observed = item(afterInsert, "minecraft:gold_ingot", 5, "offline_insert_visible");
    String removalId = "probe:offline:remove:" + playerId;
    InventoryMutation removal = new InventoryMutation(
        removalId, playerId, afterInsert.version(), List.of(),
        List.of(new InventoryRemoval(observed, 2)));
    InventoryMutationResult removed = success(inventories.compareAndApply(removal), "offline_remove");
    require(removed.removed().size() == 1 && removed.removed().get(0).count() == 2,
        "offline_partial_remove_result");
    require(removed.equals(success(inventories.compareAndApply(removal), "offline_remove_duplicate")),
        "offline_remove_idempotency");

    InventorySnapshot afterRemoval = success(inventories.snapshot(playerId, true), "offline_post_remove");
    item(afterRemoval, "minecraft:gold_ingot", 3, "offline_partial_remove_visible");
    ItemEnvelope filler = success(items.createEnvelope("minecraft:cobblestone", 1), "offline_filler");
    List<ItemEnvelope> fill = new ArrayList<>();
    for (int index = 0; index <= afterRemoval.freeSlots(); index++) fill.add(filler);
    String fillId = "probe:offline:fill:" + playerId;
    InventoryMutation fillMutation = new InventoryMutation(
        fillId, playerId, afterRemoval.version(), fill, List.of());
    InventoryMutationResult filled = success(inventories.compareAndApply(fillMutation), "offline_fill");
    require(filled.inserted().size() == afterRemoval.freeSlots()
            && filled.remainder().size() == 1,
        "offline_partial_insertion");
    require(filled.equals(success(inventories.compareAndApply(fillMutation), "offline_fill_duplicate")),
        "offline_fill_idempotency");
    InventorySnapshot after = success(inventories.snapshot(playerId, true), "offline_final_snapshot");
    require(after.freeSlots() == 0, "offline_full_inventory");
    item(after, "minecraft:gold_ingot", 3, "offline_final_item");
    return new ProbeResult("offline", playerId, after.version(), after.freeSlots(),
        after.items().size(), 14);
  }

  private static ProbeResult recovery(NativeInventoryGateway inventories, UUID playerId) {
    InventorySnapshot snapshot = success(inventories.snapshot(playerId, false), "recovery_online_snapshot");
    require(snapshot.freeSlots() == 0,
        "recovery_full_inventory freeSlots=" + snapshot.freeSlots()
            + " items=" + snapshot.items().size());
    item(snapshot, "minecraft:emerald", 1, "recovery_online_item");
    item(snapshot, "minecraft:gold_ingot", 3, "recovery_offline_item");
    item(snapshot, "minecraft:cobblestone", 1, "recovery_fill_item");
    return new ProbeResult("recovery", playerId, snapshot.version(), snapshot.freeSlots(),
        snapshot.items().size(), 4);
  }

  private static ProbeResult fixture(
      NativeInventoryGateway inventories, NativeItemCodec items, UUID playerId) {
    InventorySnapshot snapshot = success(inventories.snapshot(playerId, false), "fixture_snapshot");
    List<FixtureExpectation> expected = List.of(
        new FixtureExpectation("minecraft:diamond_sword", "wx_enchanted_durable",
            List.of("damage", "enchant")),
        new FixtureExpectation("minecraft:potion", "wx_potion", List.of("effect")),
        new FixtureExpectation("minecraft:written_book", "wx_written_book", List.of("page")),
        new FixtureExpectation("minecraft:filled_map", "wx_map", List.of("map")),
        new FixtureExpectation("minecraft:shulker_box", "wx_shulker_nested",
            List.of("nested_level_2")),
        new FixtureExpectation("minecraft:bundle", "wx_bundle_nested",
            List.of("nested_level_2")),
        new FixtureExpectation("webshopx_fixture:data_item", "wx_mod_item", List.of()));
    int assertions = 0;
    for (FixtureExpectation expectation : expected) {
      ItemEnvelope envelope = snapshot.items().stream()
          .filter(value -> value.registryId().equals(expectation.registryId()))
          .filter(value -> payload(value).contains(expectation.marker()))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(
              "fixture_missing:" + expectation.registryId() + ":" + expectation.marker()));
      String payload = payload(envelope);
      for (String token : expectation.tokens()) {
        require(payload.contains(token), "fixture_token:" + expectation.registryId() + ":" + token);
      }
      Object restored = success(items.decode(envelope, items.domain()),
          "fixture_decode:" + expectation.registryId());
      ItemEnvelope roundTrip = success(items.encode(restored, items.identity()),
          "fixture_reencode:" + expectation.registryId());
      require(envelope.payloadHash().equals(roundTrip.payloadHash()),
          "fixture_hash:" + expectation.registryId());
      require(envelope.registryId().equals(roundTrip.registryId()),
          "fixture_registry:" + expectation.registryId());
      assertions += 4 + expectation.tokens().size();
    }
    return new ProbeResult("fixture", playerId, snapshot.version(), snapshot.freeSlots(),
        snapshot.items().size(), assertions);
  }

  private static String payload(ItemEnvelope envelope) {
    return new String(envelope.payload(), StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
  }

  private static <T> T success(
      java.util.concurrent.CompletionStage<PlatformResult<T>> stage, String assertion) {
    return success(stage.toCompletableFuture().join(), assertion);
  }

  private static <T> T success(PlatformResult<T> result, String assertion) {
    if (result instanceof PlatformResult.Success<T> value) return value.value();
    throw new IllegalStateException(assertion + " result=" + describe(result));
  }

  private static boolean conflict(
      java.util.concurrent.CompletionStage<? extends PlatformResult<?>> stage) {
    return stage.toCompletableFuture().join() instanceof PlatformResult.Conflict<?>;
  }

  private static ItemEnvelope item(
      InventorySnapshot snapshot, String registryId, int count, String assertion) {
    return snapshot.items().stream()
        .filter(value -> value.registryId().equals(registryId) && value.count() == count)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(assertion));
  }

  private static void require(boolean condition, String assertion) {
    if (!condition) throw new IllegalStateException(assertion);
  }

  private static String describe(PlatformResult<?> result) {
    if (result instanceof PlatformResult.Rejected<?> rejected) return rejected.errorCode();
    if (result instanceof PlatformResult.Unavailable<?> unavailable) return unavailable.capability();
    if (result instanceof PlatformResult.Conflict<?> conflict) return "conflict:" + conflict.currentState();
    if (result instanceof PlatformResult.UnknownOutcome<?> unknown) return "unknown:" + unknown.operationId();
    return result.getClass().getSimpleName();
  }

  private static void writeEvidence(Path directory, ProbeResult result) {
    try {
      Files.createDirectories(directory);
      Path destination = directory.resolve("inventory-probe-" + result.mode() + ".json");
      Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
      String json = "{\n"
          + "  \"status\": \"passed\",\n"
          + "  \"mode\": \"" + result.mode() + "\",\n"
          + "  \"playerId\": \"" + result.playerId() + "\",\n"
          + "  \"version\": \"" + Long.toUnsignedString(result.version()) + "\",\n"
          + "  \"freeSlots\": " + result.freeSlots() + ",\n"
          + "  \"itemCount\": " + result.itemCount() + ",\n"
          + "  \"assertions\": " + result.assertions() + ",\n"
          + "  \"recordedAt\": \"" + Instant.now() + "\"\n"
          + "}\n";
      Files.writeString(temporary, json, StandardCharsets.UTF_8);
      try {
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot write inventory probe evidence", failure);
    }
  }

  private static void writeFailure(
      Path directory, String mode, UUID playerId, RuntimeException failure) {
    try {
      Files.createDirectories(directory);
      Path destination = directory.resolve("inventory-probe-" + mode + ".json");
      String reason = failure.getClass().getSimpleName() + ":"
          + String.valueOf(failure.getMessage()).replace("\\", "\\\\").replace("\"", "\\\"");
      Files.writeString(destination, "{\n"
          + "  \"status\": \"failed\",\n"
          + "  \"mode\": \"" + mode + "\",\n"
          + "  \"playerId\": \"" + playerId + "\",\n"
          + "  \"reason\": \"" + reason + "\",\n"
          + "  \"recordedAt\": \"" + Instant.now() + "\"\n"
          + "}\n", StandardCharsets.UTF_8);
    } catch (IOException ignored) {
      // The original probe failure remains authoritative when diagnostics cannot be persisted.
    }
  }

  private record ProbeResult(
      String mode, UUID playerId, long version, int freeSlots, int itemCount, int assertions) { }

  private record FixtureExpectation(String registryId, String marker, List<String> tokens) { }
}
