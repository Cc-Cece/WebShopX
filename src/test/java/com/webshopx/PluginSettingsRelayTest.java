package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PluginSettingsRelayTest {

  @Test
  void readsRelayDeploymentConfiguration() throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    config.loadFromString("""
        deployment:
          mode: relay
        relay:
          enabled: true
          endpoint: "https://relay.example.com/"
          server-id: "main"
          connector-token: "secret"
          websocket-path: "p/demo/connector/ws"
          heartbeat-seconds: 5
          reconnect-min-seconds: 0
          reconnect-max-seconds: 9999
          rpc-timeout-seconds: 1
        """);

    PluginSettings settings = PluginSettings.fromConfig(config);
    PluginSettings.RelaySettings relay = settings.relaySettings();

    assertEquals(PluginSettings.DeploymentMode.RELAY, settings.deploymentMode());
    assertTrue(relay.shouldConnect());
    assertEquals("https://relay.example.com", relay.endpoint());
    assertEquals("/p/demo/connector/ws", relay.websocketPath());
    assertEquals(10, relay.heartbeatSeconds());
    assertEquals(1, relay.reconnectMinSeconds());
    assertEquals(900, relay.reconnectMaxSeconds());
    assertEquals(2, relay.rpcTimeoutSeconds());
  }

  @Test
  void acceptsLegacyCloudflareRelayConfiguration() throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    config.loadFromString("""
        deployment:
          mode: cloudflare-relay
        cloudflare-relay:
          endpoint: "https://relay.example.com"
          server-id: "legacy"
          connector-token: "secret"
        """);

    PluginSettings settings = PluginSettings.fromConfig(config);

    assertEquals(PluginSettings.DeploymentMode.RELAY, settings.deploymentMode());
    assertEquals("legacy", settings.relaySettings().serverId());
    assertTrue(settings.relaySettings().shouldConnect());
  }

  @Test
  void explicitRelayDisablePreventsConnection() throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    config.loadFromString("""
        deployment:
          mode: relay
        relay:
          enabled: false
          endpoint: "https://relay.example.com"
          connector-token: "secret"
        """);

    PluginSettings settings = PluginSettings.fromConfig(config);

    assertEquals(PluginSettings.DeploymentMode.RELAY, settings.deploymentMode());
    assertFalse(settings.relaySettings().enabled());
    assertFalse(settings.relaySettings().shouldConnect());
  }

}
