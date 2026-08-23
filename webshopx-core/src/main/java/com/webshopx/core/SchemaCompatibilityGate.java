package com.webshopx.core;

/** Fail-closed rolling-upgrade gate for shared databases and relay events. */
public final class SchemaCompatibilityGate {
  private final int readerMinimum;
  private final int readerMaximum;
  private final int writerVersion;

  public SchemaCompatibilityGate(int readerMinimum, int readerMaximum, int writerVersion) {
    if (readerMinimum < 1 || readerMaximum < readerMinimum) throw new IllegalArgumentException("reader range");
    if (writerVersion < readerMinimum || writerVersion > readerMaximum) {
      throw new IllegalArgumentException("writer version outside reader range");
    }
    this.readerMinimum = readerMinimum;
    this.readerMaximum = readerMaximum;
    this.writerVersion = writerVersion;
  }

  public Decision assess(int storedVersion) {
    if (storedVersion < readerMinimum) return Decision.MIGRATION_REQUIRED;
    if (storedVersion > readerMaximum) return Decision.NEWER_SCHEMA_REJECTED;
    return storedVersion == writerVersion ? Decision.READ_WRITE : Decision.READ_ONLY;
  }

  public enum Decision { READ_WRITE, READ_ONLY, MIGRATION_REQUIRED, NEWER_SCHEMA_REJECTED }
}
