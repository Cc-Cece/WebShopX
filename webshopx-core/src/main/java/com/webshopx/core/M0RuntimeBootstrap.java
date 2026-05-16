package com.webshopx.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Shared M0 bootstrap used by Bukkit/Fabric/NeoForge skeletons.
 */
public final class M0RuntimeBootstrap {

  private static final String DEFAULT_HEALTH_HOST = "127.0.0.1";
  private static final int DEFAULT_HEALTH_PORT = 18080;
  private static final String DEFAULT_SQLITE_PATH = "data/webshopx-m0.sqlite";

  private M0RuntimeBootstrap() {
  }

  public static RuntimeHandle start(String runtimeId, String version, Path workingDirectory)
      throws IOException, SQLException {
    return start(runtimeId, version, workingDirectory, msg -> {
    });
  }

  public static RuntimeHandle start(String runtimeId, String version, Path workingDirectory, Consumer<String> logger)
      throws IOException, SQLException {
    Objects.requireNonNull(runtimeId, "runtimeId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(workingDirectory, "workingDirectory");
    Objects.requireNonNull(logger, "logger");

    Path runtimeRoot = workingDirectory.resolve(runtimeId);
    Files.createDirectories(runtimeRoot);

    M0BootstrapConfig config = M0BootstrapConfig.load(runtimeRoot.resolve("m0-bootstrap.properties"));
    Path sqlitePath = runtimeRoot.resolve(config.sqlitePath()).normalize();

    initDatabase(runtimeId, version, sqlitePath);
    RuntimeHealthServer healthServer = RuntimeHealthServer.start(
        config.healthHost(),
        config.healthPort(),
        runtimeId,
        version,
        sqlitePath);

    logger.accept("[WebShopX/M0] runtime=" + runtimeId
        + " version=" + version
        + " health=" + healthServer.endpoint());

    return new RuntimeHandle(runtimeId, version, runtimeRoot, sqlitePath, healthServer);
  }

  private static void initDatabase(String runtimeId, String version, Path sqlitePath) throws IOException, SQLException {
    Path parent = sqlitePath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    String jdbcUrl = "jdbc:sqlite:" + sqlitePath.toAbsolutePath();
    try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("""
            CREATE TABLE IF NOT EXISTS m0_runtime_probe (
              runtime_id TEXT PRIMARY KEY,
              version TEXT NOT NULL,
              last_boot_at TEXT NOT NULL
            )
            """);
      }
      try (PreparedStatement prepared = connection.prepareStatement("""
          INSERT INTO m0_runtime_probe (runtime_id, version, last_boot_at)
          VALUES (?, ?, ?)
          ON CONFLICT(runtime_id) DO UPDATE SET
            version = excluded.version,
            last_boot_at = excluded.last_boot_at
          """)) {
        prepared.setString(1, runtimeId);
        prepared.setString(2, version);
        prepared.setString(3, Instant.now().toString());
        prepared.executeUpdate();
      }
    }
  }

  public record RuntimeHandle(
      String runtimeId,
      String version,
      Path runtimeRoot,
      Path sqlitePath,
      RuntimeHealthServer healthServer) implements AutoCloseable {

    public String healthEndpoint() {
      return healthServer.endpoint();
    }

    @Override
    public void close() {
      healthServer.close();
    }
  }

  private record M0BootstrapConfig(String healthHost, int healthPort, String sqlitePath) {

    private static M0BootstrapConfig load(Path configPath) throws IOException {
      Properties properties = new Properties();
      if (Files.exists(configPath)) {
        try (var inputStream = Files.newInputStream(configPath)) {
          properties.load(inputStream);
        }
      } else {
        properties.setProperty("health.host", DEFAULT_HEALTH_HOST);
        properties.setProperty("health.port", Integer.toString(DEFAULT_HEALTH_PORT));
        properties.setProperty("sqlite.path", DEFAULT_SQLITE_PATH);
        Path parent = configPath.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        try (var outputStream = Files.newOutputStream(configPath)) {
          properties.store(outputStream, "WebShopX M0 bootstrap configuration");
        }
      }

      String healthHost = properties.getProperty("health.host", DEFAULT_HEALTH_HOST).trim();
      int healthPort = parsePort(properties.getProperty("health.port"));
      String sqlitePath = properties.getProperty("sqlite.path", DEFAULT_SQLITE_PATH).trim();
      return new M0BootstrapConfig(healthHost.isEmpty() ? DEFAULT_HEALTH_HOST : healthHost, healthPort, sqlitePath);
    }

    private static int parsePort(String rawPort) {
      if (rawPort == null || rawPort.isBlank()) {
        return DEFAULT_HEALTH_PORT;
      }
      try {
        int value = Integer.parseInt(rawPort.trim());
        return value > 0 && value <= 65535 ? value : DEFAULT_HEALTH_PORT;
      } catch (NumberFormatException ignored) {
        return DEFAULT_HEALTH_PORT;
      }
    }
  }

  private static final class RuntimeHealthServer implements AutoCloseable {

    private final HttpServer server;
    private final String endpoint;

    private RuntimeHealthServer(HttpServer server, String endpoint) {
      this.server = server;
      this.endpoint = endpoint;
    }

    private static RuntimeHealthServer start(
        String host,
        int requestedPort,
        String runtimeId,
        String version,
        Path sqlitePath)
        throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress(host, requestedPort), 0);
      server.createContext("/health", new HealthHandler(runtimeId, version, sqlitePath));
      server.start();
      int boundPort = server.getAddress().getPort();
      return new RuntimeHealthServer(server, "http://" + host + ":" + boundPort + "/health");
    }

    private String endpoint() {
      return endpoint;
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private static final class HealthHandler implements HttpHandler {

    private final String responseBody;

    private HealthHandler(String runtimeId, String version, Path sqlitePath) {
      this.responseBody = "{" +
          "\"status\":\"ok\"," +
          "\"runtime\":\"" + jsonEscape(runtimeId) + "\"," +
          "\"version\":\"" + jsonEscape(version) + "\"," +
          "\"sqlite\":\"" + jsonEscape(sqlitePath.toAbsolutePath().toString()) + "\"" +
          "}";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream outputStream = exchange.getResponseBody()) {
        outputStream.write(body);
      }
    }
  }

  private static String jsonEscape(String raw) {
    StringBuilder builder = new StringBuilder(raw.length() + 16);
    for (int i = 0; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      switch (ch) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> builder.append(ch);
      }
    }
    return builder.toString();
  }
}
