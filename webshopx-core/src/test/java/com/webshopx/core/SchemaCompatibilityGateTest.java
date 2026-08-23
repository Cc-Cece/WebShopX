package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SchemaCompatibilityGateTest {
  @Test
  void failClosedAcrossRollingUpgradeBoundaries() {
    SchemaCompatibilityGate gate = new SchemaCompatibilityGate(2, 4, 3);
    assertEquals(SchemaCompatibilityGate.Decision.MIGRATION_REQUIRED, gate.assess(1));
    assertEquals(SchemaCompatibilityGate.Decision.READ_ONLY, gate.assess(2));
    assertEquals(SchemaCompatibilityGate.Decision.READ_WRITE, gate.assess(3));
    assertEquals(SchemaCompatibilityGate.Decision.READ_ONLY, gate.assess(4));
    assertEquals(SchemaCompatibilityGate.Decision.NEWER_SCHEMA_REJECTED, gate.assess(5));
  }
}
