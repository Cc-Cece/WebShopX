package com.webshopx.loader;

import com.webshopx.core.ItemEnvelopeBinaryCodec;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.SupplyInventoryGateway.SupplyWithdrawal;
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
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Atomic restart journal for native supply-container withdrawals. */
final class SupplyOperationStore {
  private static final int MAGIC = 0x5758534f;
  private static final int FORMAT = 1;
  private static final int MAX_RESULTS = 10_000;
  private static final int MAX_ENVELOPE_BYTES = 16 * 1024 * 1024;
  private final Path directory;
  private final ItemEnvelopeBinaryCodec envelopes = new ItemEnvelopeBinaryCodec();

  SupplyOperationStore(Path dataDirectory) {
    directory = dataDirectory == null ? null : dataDirectory.resolve("supply-operations");
    if (directory != null) {
      try {
        Files.createDirectories(directory);
        prune();
      } catch (IOException failure) {
        throw new IllegalStateException("Cannot initialize supply operation journal", failure);
      }
    }
  }

  Optional<SupplyWithdrawal> read(String operationId) {
    if (directory == null || !Files.isRegularFile(file(operationId))) return Optional.empty();
    try (DataInputStream input = new DataInputStream(
        new BufferedInputStream(Files.newInputStream(file(operationId))))) {
      if (input.readInt() != MAGIC || input.readInt() != FORMAT) return Optional.empty();
      if (!operationId.equals(readString(input))) return Optional.empty();
      long version = input.readLong();
      int quantity = input.readInt();
      ItemEnvelope removed = null;
      int length = input.readInt();
      if (length < 0 || length > MAX_ENVELOPE_BYTES) return Optional.empty();
      if (length > 0) removed = envelopes.decode(input.readNBytes(length));
      if (input.read() != -1) return Optional.empty();
      return Optional.of(new SupplyWithdrawal(version, removed, quantity));
    } catch (IOException | RuntimeException corrupt) {
      return Optional.empty();
    }
  }

  boolean pending(String operationId) {
    return directory != null && Files.isRegularFile(pendingFile(operationId));
  }

  boolean begin(String operationId) {
    if (directory == null) return true;
    try {
      Files.writeString(pendingFile(operationId), operationId, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      return true;
    } catch (java.nio.file.FileAlreadyExistsException concurrent) {
      return false;
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot persist supply operation intent", failure);
    }
  }

  void clearPending(String operationId) {
    if (directory == null) return;
    try {
      Files.deleteIfExists(pendingFile(operationId));
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot clear supply operation intent", failure);
    }
  }

  void write(String operationId, SupplyWithdrawal result) {
    if (directory == null) return;
    Path target = file(operationId);
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      byte[] envelope = result.removed() == null ? new byte[0] : envelopes.encode(result.removed());
      if (envelope.length > MAX_ENVELOPE_BYTES) throw new IOException("Supply result is too large");
      try (DataOutputStream output = new DataOutputStream(
          new BufferedOutputStream(Files.newOutputStream(temporary)))) {
        output.writeInt(MAGIC);
        output.writeInt(FORMAT);
        writeString(output, operationId);
        output.writeLong(result.version());
        output.writeInt(result.removedQuantity());
        output.writeInt(envelope.length);
        output.write(envelope);
      }
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
      Files.deleteIfExists(pendingFile(operationId));
      prune();
    } catch (IOException failure) {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException ignored) {
        // An incomplete temporary file is never a completed operation.
      }
      throw new IllegalStateException("Cannot persist supply operation result", failure);
    }
  }

  private void prune() throws IOException {
    if (directory == null || !Files.isDirectory(directory)) return;
    try (var files = Files.list(directory)) {
      List<Path> completed = files.filter(path -> path.getFileName().toString().endsWith(".bin"))
          .sorted(Comparator.comparingLong(SupplyOperationStore::lastModified).reversed()).toList();
      for (int index = MAX_RESULTS; index < completed.size(); index++) Files.deleteIfExists(completed.get(index));
    }
  }

  private Path file(String operationId) {
    return directory.resolve(hex(sha256(operationId)) + ".bin");
  }

  private Path pendingFile(String operationId) {
    return directory.resolve(hex(sha256(operationId)) + ".pending");
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 512) throw new IOException("Operation id is too long");
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String readString(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > 512) throw new IOException("Invalid operation id length");
    byte[] value = input.readNBytes(length);
    if (value.length != length) throw new IOException("Truncated operation id");
    return new String(value, StandardCharsets.UTF_8);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String hex(byte[] value) {
    StringBuilder output = new StringBuilder(value.length * 2);
    for (byte item : value) output.append(String.format("%02x", item & 0xff));
    return output.toString();
  }

  private static long lastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException ignored) {
      return Long.MIN_VALUE;
    }
  }
}
