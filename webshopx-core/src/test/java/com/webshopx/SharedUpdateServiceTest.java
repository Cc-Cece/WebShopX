package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.webshopx.platform.PlatformIdentity;
import java.net.http.HttpClient;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SharedUpdateServiceTest {
  @Test
  void selectsOnlyTheExactLoaderAndMinecraftArtifact() {
    SharedUpdateService service = new SharedUpdateService(
        new PlatformIdentity("fabric", "fabric", "1.20.1", "0.16", "node", "sha256:test"),
        "v2.0.0",
        HttpClient.newHttpClient());
    JsonArray versions = JsonParser.parseString("""
        [
          {"version_type":"release","version_number":"3.0.0","name":"Paper build",
           "date_published":"2026-01-01T00:00:00Z","game_versions":["1.20.1"],
           "loaders":["paper"],"files":[{"primary":true,"filename":"paper.jar",
           "url":"https://cdn.example/paper.jar"}]},
          {"version_type":"release","version_number":"3.0.0","name":"Fabric build",
           "date_published":"2026-01-02T00:00:00Z","game_versions":["1.20.1"],
           "loaders":["fabric"],"files":[{"primary":true,"filename":"fabric.jar",
           "url":"https://cdn.example/fabric.jar"}]}
        ]
        """).getAsJsonArray();

    JsonObject result = service.evaluate(versions, Instant.parse("2026-01-03T00:00:00Z"));
    assertEquals("update_available", result.get("status").getAsString());
    assertTrue(result.get("compatible").getAsBoolean());
    assertEquals("fabric.jar", result.get("fileName").getAsString());
    assertEquals("fabric", result.get("loader").getAsString());
    assertEquals("1.20.1", result.get("minecraftVersion").getAsString());
  }

  @Test
  void rejectsLatestReleaseWhenNoExactLoaderBuildExists() {
    SharedUpdateService service = new SharedUpdateService(
        new PlatformIdentity("neoforge", "neoforge", "1.21.1", "21.1", "node", "sha256:test"),
        "2.0.0",
        HttpClient.newHttpClient());
    JsonArray versions = JsonParser.parseString("""
        [{"version_type":"release","version_number":"3.0.0","name":"Forge build",
          "date_published":"2026-01-01T00:00:00Z","game_versions":["1.21.1"],
          "loaders":["forge"],"files":[{"primary":true,"filename":"forge.jar",
          "url":"https://cdn.example/forge.jar"}]}]
        """).getAsJsonArray();

    JsonObject result = service.evaluate(versions, Instant.parse("2026-01-03T00:00:00Z"));
    assertEquals("no_compatible_build", result.get("status").getAsString());
    assertFalse(result.get("compatible").getAsBoolean());
    assertEquals("", result.get("downloadUrl").getAsString());
  }
}
