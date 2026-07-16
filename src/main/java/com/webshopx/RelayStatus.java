package com.webshopx;

import java.time.Instant;

record RelayStatus(
    boolean enabled,
    boolean connected,
    String endpoint,
    String serverId,
    Instant connectedAt,
    Instant lastHeartbeatAt,
    long reconnectCount,
    long requestsReceived,
    long requestsSucceeded,
    long requestsFailed,
    String lastError) {
  static RelayStatus disabled(PluginSettings.RelaySettings settings) {
    return new RelayStatus(
        settings != null && settings.enabled(),
        false,
        settings == null ? "" : settings.endpoint(),
        settings == null ? "main" : settings.serverId(),
        null,
        null,
        0L,
        0L,
        0L,
        0L,
        settings == null || settings.shouldConnect() ? null : "Relay endpoint or connector token is not configured");
  }
}
