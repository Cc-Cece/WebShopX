package com.webshopx.loader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Cross-classloader guard preventing Paper and Mod bootstraps from running together. */
final class RuntimeInstanceGuard implements AutoCloseable {
  private final FileChannel channel;
  private final FileLock lock;

  private RuntimeInstanceGuard(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  static RuntimeInstanceGuard acquire(Path dataDirectory, String owner) {
    try {
      Files.createDirectories(dataDirectory);
      Path lockPath = dataDirectory.resolve("runtime.lock");
      FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
          StandardOpenOption.READ, StandardOpenOption.WRITE);
      try {
        FileLock lock = channel.tryLock();
        if (lock == null) throw new IllegalStateException("another WebShopX runtime owns " + lockPath);
        channel.truncate(0);
        channel.write(StandardCharsets.UTF_8.encode(owner + System.lineSeparator()));
        channel.force(true);
        return new RuntimeInstanceGuard(channel, lock);
      } catch (RuntimeException error) {
        channel.close();
        throw new IllegalStateException("another WebShopX runtime owns " + lockPath, error);
      }
    } catch (IOException error) {
      throw new IllegalStateException("cannot acquire WebShopX runtime lock", error);
    }
  }

  @Override public void close() {
    try {
      if (lock.isValid()) lock.release();
      channel.close();
    } catch (IOException error) {
      throw new IllegalStateException("cannot release WebShopX runtime lock", error);
    }
  }
}
