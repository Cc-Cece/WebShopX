package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SqliteConcurrencyRetryTest {
  @Test
  void sqliteWriteContentionShouldRecoverWithBoundedRetries() throws Exception {
    Path dbFile = Files.createTempFile("webshopx-concurrency-", ".db");
    Files.deleteIfExists(dbFile);
    Class.forName("org.sqlite.JDBC");

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
      execute(connection, "PRAGMA journal_mode=WAL");
      execute(connection, "PRAGMA synchronous=NORMAL");
      execute(connection, "PRAGMA busy_timeout=3000");
      execute(connection, "CREATE TABLE counter (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)");
      execute(connection, "INSERT INTO counter(id, value) VALUES (1, 0)");
    }

    int threads = 8;
    int incrementsPerThread = 120;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      tasks.add(() -> runWorker(dbFile, incrementsPerThread));
    }

    List<Future<Integer>> futures = pool.invokeAll(tasks);
    pool.shutdown();
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

    int failedAttempts = 0;
    for (Future<Integer> future : futures) {
      failedAttempts += future.get();
    }

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        PreparedStatement st = connection.prepareStatement("SELECT value FROM counter WHERE id = 1");
        ResultSet rs = st.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(threads * incrementsPerThread - failedAttempts, rs.getInt("value"));
    } finally {
      Files.deleteIfExists(dbFile);
    }

    // Bounded retries should absorb contention and avoid widespread failures.
    assertTrue(failedAttempts < threads);
  }

  private static int runWorker(Path dbFile, int rounds) throws Exception {
    int exhausted = 0;
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath())) {
      execute(connection, "PRAGMA busy_timeout=3000");
      for (int i = 0; i < rounds; i++) {
        if (!incrementWithRetry(connection)) {
          exhausted++;
        }
      }
    }
    return exhausted;
  }

  private static boolean incrementWithRetry(Connection connection) throws Exception {
    int[] backoffMs = {10, 50, 100, 150, 200};
    for (int attempt = 0; attempt < backoffMs.length; attempt++) {
      try {
        execute(connection, "BEGIN IMMEDIATE");
        int current;
        try (PreparedStatement read = connection.prepareStatement("SELECT value FROM counter WHERE id = 1");
            ResultSet rs = read.executeQuery()) {
          rs.next();
          current = rs.getInt("value");
        }
        try (PreparedStatement write =
            connection.prepareStatement("UPDATE counter SET value = ? WHERE id = 1")) {
          write.setInt(1, current + 1);
          write.executeUpdate();
        }
        execute(connection, "COMMIT");
        return true;
      } catch (SQLException exception) {
        safeRollback(connection);
        if (!isBusy(exception) || attempt == backoffMs.length - 1) {
          return false;
        }
        Thread.sleep(backoffMs[attempt]);
      }
    }
    return false;
  }

  private static boolean isBusy(SQLException exception) {
    String message = exception.getMessage();
    return message != null
        && (message.toLowerCase().contains("database is locked")
            || message.toLowerCase().contains("busy"));
  }

  private static void safeRollback(Connection connection) {
    try {
      execute(connection, "ROLLBACK");
    } catch (Exception ignored) {
      // No-op if transaction wasn't active.
    }
  }

  private static void execute(Connection c, String sql) throws Exception {
    try (PreparedStatement st = c.prepareStatement(sql)) {
      st.execute();
    }
  }
}
