package com.webshopx.core;

import com.webshopx.platform.PlatformPorts.PlatformEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;

/** Durable at-most-once admission for relay consumers. Business work stays in the caller transaction. */
public final class JdbcEventInbox {
  private final DataSource dataSource;
  private final Clock clock;

  public JdbcEventInbox(DataSource dataSource, Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public void initialize() throws SQLException {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(
             "CREATE TABLE IF NOT EXISTS webshopx_event_inbox ("
                 + "event_id VARCHAR(128) PRIMARY KEY, event_type VARCHAR(128) NOT NULL, "
                 + "schema_version INTEGER NOT NULL, server_id VARCHAR(128) NOT NULL, "
                 + "occurred_at BIGINT NOT NULL, admitted_at BIGINT NOT NULL)")) {
      statement.execute();
    }
  }

  public boolean admit(PlatformEvent event) throws SQLException {
    Objects.requireNonNull(event, "event");
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(
             "INSERT INTO webshopx_event_inbox "
                 + "(event_id,event_type,schema_version,server_id,occurred_at,admitted_at) "
                 + "VALUES (?,?,?,?,?,?)")) {
      statement.setString(1, event.id());
      statement.setString(2, event.type());
      statement.setInt(3, event.schemaVersion());
      statement.setString(4, event.serverId());
      statement.setLong(5, event.occurredAtEpochMillis());
      statement.setLong(6, clock.millis());
      statement.executeUpdate();
      return true;
    } catch (SQLException duplicate) {
      if (isConstraintViolation(duplicate)) return false;
      throw duplicate;
    }
  }

  public int purge(Duration retention) throws SQLException {
    if (retention.isNegative() || retention.isZero()) throw new IllegalArgumentException("retention");
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(
             "DELETE FROM webshopx_event_inbox WHERE admitted_at < ?")) {
      statement.setLong(1, clock.millis() - retention.toMillis());
      return statement.executeUpdate();
    }
  }

  private static boolean isConstraintViolation(SQLException error) {
    for (SQLException current = error; current != null; current = current.getNextException()) {
      String state = current.getSQLState();
      if (state != null && state.startsWith("23")) return true;
      if (current.getErrorCode() == 19 || current.getErrorCode() == 1062) return true;
    }
    return false;
  }
}
