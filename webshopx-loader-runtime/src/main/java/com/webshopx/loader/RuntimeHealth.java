package com.webshopx.loader;

import com.webshopx.core.WebShopXCoreRuntime;
import com.webshopx.platform.CapabilitySnapshot;
import com.webshopx.platform.PlatformIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;

/** Atomic machine-readable health snapshot for server consoles, panels and watchdogs. */
final class RuntimeHealth {
  private RuntimeHealth() { }

  static void write(Path dataDirectory, PlatformIdentity identity,
                    CapabilitySnapshot capabilities, WebShopXCoreRuntime.State state) {
    try {
      Files.createDirectories(dataDirectory);
      Path target = dataDirectory.resolve("health.json");
      Path pending = dataDirectory.resolve("health.json.tmp");
      StringBuilder states = new StringBuilder();
      capabilities.states().entrySet().stream()
          .sorted(Comparator.comparing(entry -> entry.getKey().name()))
          .forEach(entry -> {
            if (states.length() > 0) states.append(',');
            states.append('"').append(entry.getKey().name()).append("\":\"")
                .append(entry.getValue().status().name()).append('"');
          });
      String json = "{\n"
          + "  \"schemaVersion\": 1,\n"
          + "  \"capturedAt\": \"" + Instant.now() + "\",\n"
          + "  \"state\": \"" + state.name() + "\",\n"
          + "  \"serverId\": \"" + escape(identity.serverId()) + "\",\n"
          + "  \"platform\": \"" + escape(identity.platform()) + "\",\n"
          + "  \"loader\": \"" + escape(identity.loader()) + "\",\n"
          + "  \"minecraft\": \"" + escape(identity.minecraftVersion()) + "\",\n"
          + "  \"loaderVersion\": \"" + escape(identity.loaderVersion()) + "\",\n"
          + "  \"capabilities\": {" + states + "}\n"
          + "}\n";
      Files.writeString(pending, json, StandardCharsets.UTF_8);
      try {
        Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicUnsupported) {
        Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException error) {
      throw new IllegalStateException("cannot write WebShopX health snapshot", error);
    }
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
