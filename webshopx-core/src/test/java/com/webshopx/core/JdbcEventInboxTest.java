package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.PlatformPorts.PlatformEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

class JdbcEventInboxTest {
  @Test
  void duplicateEventIsAdmittedOnlyOnceDurably() throws Exception {
    SQLiteDataSource source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:file:event-inbox?mode=memory&cache=shared");
    try (var keeper = source.getConnection()) {
      JdbcEventInbox inbox = new JdbcEventInbox(source,
          Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));
      inbox.initialize();
      PlatformEvent event = new PlatformEvent("evt-1", "delivery.ready", 1, "paper-a",
          new CompatibilityDomain("paper", "paper", "1.20.1", 1, "vanilla"), 100L, "{}");
      assertTrue(inbox.admit(event));
      assertFalse(inbox.admit(event));
    }
  }
}
