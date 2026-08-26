package com.webshopx.loader;

import com.webshopx.core.ItemEnvelopeBinaryCodec;
import com.webshopx.platform.InventoryTypes.InventoryMutationResult;
import com.webshopx.platform.ItemEnvelope;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Atomic, bounded restart journal for completed native inventory mutations. */
final class InventoryOperationStore {
  private static final int MAGIC = 0x5758494F;
  private static final int FORMAT = 1;
  private static final int MAX_RESULTS = 10_000;
  private static final int MAX_ITEMS = 256;
  private static final int MAX_ENVELOPE_BYTES = 16 * 1024 * 1024;
  private final Path directory;
  private final ItemEnvelopeBinaryCodec envelopes = new ItemEnvelopeBinaryCodec();

  InventoryOperationStore(Path dataDirectory) {
    directory = dataDirectory == null ? null : dataDirectory.resolve("inventory-operations");
    if (directory != null) {
      try {
        Files.createDirectories(directory);
        prune();
      } catch (IOException failure) {
        throw new IllegalStateException("Cannot initialize inventory operation journal", failure);
      }
    }
  }

  Optional<InventoryMutationResult> read(String operationId) {
    if (directory == null) return Optional.empty();
    Path file = file(operationId);
    if (!Files.isRegularFile(file)) return Optional.empty();
    try (DataInputStream input = new DataInputStream(
        new BufferedInputStream(Files.newInputStream(file)))) {
      if (input.readInt() != MAGIC || input.readInt() != FORMAT) return Optional.empty();
      if (!operationId.equals(readString(input, 512))) return Optional.empty();
      long newVersion = input.readLong();
      List<ItemEnvelope> inserted = readItems(input);
      List<ItemEnvelope> removed = readItems(input);
      List<ItemEnvelope> remainder = readItems(input);
      if (input.read() != -1) return Optional.empty();
      return Optional.of(new InventoryMutationResult(newVersion, inserted, removed, remainder));
    } catch (IOException | RuntimeException corrupt) {
      return Optional.empty();
    }
  }

  void write(String operationId, InventoryMutationResult result) {
    if (directory == null) return;
    Path target = file(operationId);
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      Files.createDirectories(directory);
      try (DataOutputStream output = new DataOutputStream(
          new BufferedOutputStream(Files.newOutputStream(temporary)))) {
        output.writeInt(MAGIC);
        output.writeInt(FORMAT);
        writeString(output, operationId);
        output.writeLong(result.newVersion());
        writeItems(output, result.inserted());
        writeItems(output, result.removed());
        writeItems(output, result.remainder());
      }
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
      prune();
    } catch (IOException failure) {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException ignored) {
        // The incomplete .tmp file is never considered a completed operation.
      }
      throw new IllegalStateException("Cannot persist inventory operation result", failure);
    }
  }

  private void writeItems(DataOutputStream output, List<ItemEnvelope> items) throws IOException {
    if (items.size() > MAX_ITEMS) throw new IOException("Too many inventory result items");
    output.writeInt(items.size());
    for (ItemEnvelope item : items) {
      byte[] encoded = envelopes.encode(item);
      if (encoded.length > MAX_ENVELOPE_BYTES) throw new IOException("Inventory result is too large");
      output.writeInt(encoded.length);
      output.write(encoded);
    }
  }

  private List<ItemEnvelope> readItems(DataInputStream input) throws IOException {
    int count = input.readInt();
    if (count < 0 || count > MAX_ITEMS) throw new IOException("Invalid inventory result count");
    List<ItemEnvelope> items = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      int length = input.readInt();
      if (length < 1 || length > MAX_ENVELOPE_BYTES) throw new IOException("Invalid envelope length");
      items.add(envelopes.decode(readExactly(input, length)));
    }
    return List.copyOf(items);
  }

  private void prune() throws IOException {
    if (directory == null || !Files.isDirectory(directory)) return;
    try (var files = Files.list(directory)) {
      List<Path> completed = files
          .filter(path -> path.getFileName().toString().endsWith(".bin"))
          .sorted(Comparator.comparingLong(InventoryOperationStore::lastModified).reversed())
          .toList();
      for (int index = MAX_RESULTS; index < completed.size(); index++) {
        Files.deleteIfExists(completed.get(index));
      }
    }
  }

  private Path file(String operationId) {
    return directory.resolve(hex(sha256(operationId.getBytes(StandardCharsets.UTF_8))) + ".bin");
  }

  private static long lastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException ignored) {
      return Long.MIN_VALUE;
    }
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 512) throw new IOException("Operation id is too long");
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String readString(DataInputStream input, int maximum) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > maximum) throw new IOException("Invalid string length");
    return new String(readExactly(input, length), StandardCharsets.UTF_8);
  }

  private static byte[] readExactly(DataInputStream input, int length) throws IOException {
    byte[] value = input.readNBytes(length);
    if (value.length != length) throw new IOException("Unexpected end of inventory operation file");
    return value;
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String hex(byte[] value) {
    StringBuilder output = new StringBuilder(value.length * 2);
    for (byte item : value) output.append(String.format("%02x", item & 0xff));
    return output.toString();
  }
}
