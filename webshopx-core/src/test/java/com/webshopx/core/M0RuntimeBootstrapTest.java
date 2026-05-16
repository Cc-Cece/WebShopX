package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class M0RuntimeBootstrapTest {

  @Test
  void bootstrapInitializesSqliteAndHealthEndpoint() throws Exception {
    Path tempRoot = Files.createTempDirectory("webshopx-core-m0-test-");
    Path runtimeRoot = tempRoot.resolve("fabric");
    Files.createDirectories(runtimeRoot);
    Path configPath = runtimeRoot.resolve("m0-bootstrap.properties");
    Files.writeString(configPath, String.join(System.lineSeparator(),
        "health.host=127.0.0.1",
        "health.port=0",
        "sqlite.path=data/test.sqlite"));

    try (M0RuntimeBootstrap.RuntimeHandle handle = M0RuntimeBootstrap.start(
        "fabric",
        "test",
        tempRoot,
        ignored -> {
        })) {
      assertTrue(handle.healthEndpoint().contains("/health"));
      assertTrue(Files.exists(handle.sqlitePath()));
    }
  }
}
