package com.webshopx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.stream.Stream;
import org.bukkit.plugin.java.JavaPlugin;

class BusinessLedgerLogService {
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final JavaPlugin plugin;
  private volatile PluginSettings.BusinessLedgerSettings settings;

  BusinessLedgerLogService(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  void apply(PluginSettings.BusinessLedgerSettings settings) {
    this.settings = settings;
    if (settings == null || !settings.enabled()) {
      return;
    }
    Path dir = resolveLogDirectory(settings.directory());
    try {
      Files.createDirectories(dir);
      cleanupOldLogs(settings);
    } catch (IOException exception) {
      plugin.getLogger().warning("Failed to initialize business ledger log directory: " + exception.getMessage());
    }
  }

  void close() {
    // No open handler to close; writes are append-only per event.
  }

  void logWalletLedger(
      long userId,
      String username,
      long walletId,
      CurrencyType currency,
      long delta,
      String bizType,
      String bizId,
      String tradeType,
      String itemDetail,
      boolean enforceBalance,
      boolean gameCoinBackedByVault) {
    PluginSettings.BusinessLedgerSettings current = this.settings;
    if (current == null || !current.enabled()) {
      return;
    }

    String nowDate = LocalDate.now(ZoneId.systemDefault()).format(DATE_FORMATTER);
    Path file = resolveLogDirectory(current.directory()).resolve("business-ledger-" + nowDate + ".log");
    String line = buildReadableLine(
        userId,
        username,
        walletId,
        currency,
        delta,
        bizType,
        bizId,
        tradeType,
        itemDetail,
        enforceBalance,
        gameCoinBackedByVault);
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          line + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException exception) {
      plugin.getLogger().warning("Failed to write business ledger log: " + exception.getMessage());
    }
  }

  private String buildReadableLine(
      long userId,
      String username,
      long walletId,
      CurrencyType currency,
      long delta,
      String bizType,
      String bizId,
      String tradeType,
      String itemDetail,
      boolean enforceBalance,
      boolean gameCoinBackedByVault) {
    String ts = OffsetDateTime.now().toString();
    String currencyText = currency == null ? "UNKNOWN" : currency.name();
    String sign = delta >= 0 ? "+" : "";
    return String.format(
        Locale.ROOT,
        "[%s] user=%s(id=%d) wallet=%d balance_delta %s %s%d | biz=%s(%s) | trade_type=%s | item=%s | enforce_balance=%s | game_coin_vault_backed=%s",
        ts,
        safeText(username),
        userId,
        walletId,
        currencyText,
        sign,
        delta,
        safeText(bizType),
        safeText(bizId),
        safeText(tradeType),
        safeText(itemDetail),
        yesNo(enforceBalance),
        yesNo(gameCoinBackedByVault));
  }

  private String safeText(String raw) {
    if (raw == null) {
      return "-";
    }
    String value = raw.replace('\r', ' ').replace('\n', ' ').trim();
    return value.isEmpty() ? "-" : value;
  }

  private String yesNo(boolean value) {
    return value ? "yes" : "no";
  }

  private void cleanupOldLogs(PluginSettings.BusinessLedgerSettings settings) {
    if (settings == null || settings.retentionDays() <= 0) {
      return;
    }
    Path dir = resolveLogDirectory(settings.directory());
    if (!Files.exists(dir)) {
      return;
    }
    Instant cutoff = Instant.now().minus(settings.retentionDays(), ChronoUnit.DAYS);
    try (Stream<Path> stream = Files.list(dir)) {
      stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".log"))
          .forEach(path -> tryDeleteIfExpired(path, cutoff));
    } catch (IOException exception) {
      plugin.getLogger().warning("Failed to clean old business ledger logs: " + exception.getMessage());
    }
  }

  private void tryDeleteIfExpired(Path path, Instant cutoff) {
    try {
      FileTime modifiedAt = Files.getLastModifiedTime(path);
      if (modifiedAt.toInstant().isBefore(cutoff)) {
        Files.deleteIfExists(path);
      }
    } catch (IOException exception) {
      plugin.getLogger().warning("Failed to delete old business ledger log: " + path);
    }
  }

  private Path resolveLogDirectory(String configured) {
    String directory = configured == null || configured.isBlank()
        ? "logs/business-ledger"
        : configured.trim();
    return plugin.getDataFolder().toPath().resolve(directory);
  }
}
