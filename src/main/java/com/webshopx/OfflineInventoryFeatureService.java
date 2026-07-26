package com.webshopx;

import com.google.gson.JsonObject;
import java.time.Instant;
import org.bukkit.plugin.java.JavaPlugin;

final class OfflineInventoryFeatureService {
  static final int RISK_ACK_VERSION = 1;
  private final JavaPlugin plugin;
  private final RuntimeConfigService runtimeConfigService;

  OfflineInventoryFeatureService(JavaPlugin plugin, RuntimeConfigService runtimeConfigService) {
    this.plugin = plugin;
    this.runtimeConfigService = runtimeConfigService;
  }

  State state() {
    RuntimeConfigService.ConfigDocument document = runtimeConfigService.readOfflineInventoryConfig();
    JsonObject config = document.config();
    boolean requested = config.has("enabled") && config.get("enabled").getAsBoolean();
    boolean officialShopCaptureEnabled =
        config.has("officialShopCaptureEnabled")
            && config.get("officialShopCaptureEnabled").getAsBoolean();
    boolean forceDisabled =
        plugin.getConfig().getBoolean("safety.force-disable-offline-inventory-write", false);
    return new State(
        requested && !forceDisabled,
        requested,
        forceDisabled,
        officialShopCaptureEnabled,
        config.has("enabledAt") ? config.get("enabledAt").getAsString() : null,
        config.has("enabledBy") ? config.get("enabledBy").getAsString() : null,
        config.has("riskAckVersion") ? config.get("riskAckVersion").getAsInt() : 0,
        document.version());
  }

  long update(
      boolean enabled,
      boolean acknowledged,
      boolean officialShopCaptureEnabled,
      String administrator) {
    State previous = state();
    if (enabled && !previous.requested()
        && (!acknowledged || administrator == null || administrator.isBlank())) {
      throw new ServiceException(
          "risk_ack_required", "The current offline inventory risk notice must be acknowledged");
    }
    JsonObject config = runtimeConfigService.readOfflineInventoryConfig().config().deepCopy();
    config.addProperty("enabled", enabled);
    config.addProperty("officialShopCaptureEnabled", officialShopCaptureEnabled);
    if (enabled && !previous.requested()) {
      config.addProperty("enabledAt", Instant.now().toString());
      config.addProperty("enabledBy", administrator);
      config.addProperty("riskAckVersion", RISK_ACK_VERSION);
    } else if (!enabled) {
      config.remove("enabledAt");
      config.remove("enabledBy");
      config.addProperty("riskAckVersion", 0);
    }
    return runtimeConfigService.updateOfflineInventoryConfig(config);
  }

  void requireWriteEnabled() {
    State current = state();
    if (!current.enabled()) {
      throw new ServiceException(
          "offline_write_disabled",
          current.forceDisabled()
              ? "Offline inventory writes are disabled by the local kill switch"
              : "Offline inventory writes are disabled by the administrator");
    }
  }

  void requireOfficialShopCaptureEnabled() {
    if (!state().officialShopCaptureEnabled()) {
      throw new ServiceException(
          "offline_official_capture_disabled",
          "Offline official-shop capture is disabled by the administrator");
    }
  }

  record State(
      boolean enabled,
      boolean requested,
      boolean forceDisabled,
      boolean officialShopCaptureEnabled,
      String enabledAt,
      String enabledBy,
      int riskAckVersion,
      long version) {}
}
