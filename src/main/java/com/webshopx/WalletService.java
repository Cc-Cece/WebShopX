package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

class WalletService {
  private final JavaPlugin plugin;
  private final DatabaseManager databaseManager;
  private final SqlProvider sqlProvider;
  private final Supplier<PluginSettings> settingsSupplier;

  private volatile Economy vaultEconomy;
  private volatile String vaultProviderName;

  WalletService(
      JavaPlugin plugin,
      DatabaseManager databaseManager,
      Supplier<PluginSettings> settingsSupplier) {
    this.plugin = plugin;
    this.databaseManager = databaseManager;
    this.sqlProvider = databaseManager.sqlProvider();
    this.settingsSupplier = settingsSupplier;
    refreshVaultHook();
  }

  void refreshVaultHook() {
    Plugin vaultPlugin = plugin.getServer().getPluginManager().getPlugin("Vault");
    if (vaultPlugin == null || !vaultPlugin.isEnabled()) {
      this.vaultEconomy = null;
      this.vaultProviderName = null;
      return;
    }

    RegisteredServiceProvider<Economy> provider = plugin.getServer()
        .getServicesManager()
        .getRegistration(Economy.class);
    if (provider == null || provider.getProvider() == null) {
      this.vaultEconomy = null;
      this.vaultProviderName = null;
      return;
    }
    this.vaultEconomy = provider.getProvider();
    this.vaultProviderName = provider.getProvider().getName();
  }

  GameCoinIntegrationStatus getGameCoinIntegrationStatus() {
    refreshVaultHook();
    boolean vaultPluginPresent = plugin.getServer().getPluginManager().getPlugin("Vault") != null;
    Economy economy = this.vaultEconomy;
    return new GameCoinIntegrationStatus(
        vaultPluginPresent,
        economy != null,
        economy == null ? null : (vaultProviderName == null ? economy.getName() : vaultProviderName),
        economy != null);
  }

  WalletBalance getBalance(long userId) {
    return databaseManager.withConnection(connection -> {
      ensureWallet(connection, userId);
      RawWalletBalance raw = readRawBalance(connection, userId, false);
      if (!isGameCoinBackedByVault()) {
        return new WalletBalance(raw.shopCoin(), raw.gameCoin());
      }
      GameCoinAccount account = readGameCoinAccount(connection, userId, false);
      long gameCoin = readVaultBalance(account);
      if (raw.gameCoin() != gameCoin) {
        updateGameCoinMirror(connection, raw.walletId(), gameCoin);
      }
      return new WalletBalance(raw.shopCoin(), gameCoin);
    });
  }

  List<LedgerEntry> listRecentLedger(long userId, int limit) {
    int normalizedLimit = Math.max(1, Math.min(limit, 50));
    return databaseManager.withConnection(connection -> {
      ensureWallet(connection, userId);
      long walletId = readWalletId(connection, userId, false);
      String sql = """
          SELECT currency, delta, biz_type, biz_id, created_at
          FROM wallet_ledger
          WHERE wallet_id = ?
          ORDER BY id DESC
          LIMIT ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, walletId);
        statement.setInt(2, normalizedLimit);
        List<LedgerEntry> entries = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            entries.add(new LedgerEntry(
                CurrencyType.valueOf(resultSet.getString("currency")),
                resultSet.getLong("delta"),
                resultSet.getString("biz_type"),
                resultSet.getString("biz_id"),
                resultSet.getTimestamp("created_at").toLocalDateTime()));
          }
        }
        return entries;
      }
    });
  }

  WalletBalance exchange(
      long userId,
      CurrencyType fromCurrency,
      CurrencyType toCurrency,
      long amount,
      String idempotencyKey) {
    if (fromCurrency == toCurrency) {
      throw new ServiceException("invalid_exchange", "Exchange currency direction is invalid");
    }
    if (amount <= 0) {
      throw new ServiceException("invalid_amount", "Exchange amount must be positive");
    }

    PluginSettings.ExchangeDirection direction =
        settingsSupplier.get().exchangeSettings().direction(fromCurrency, toCurrency);
    if (!direction.enabled()) {
      throw new ServiceException("exchange_disabled", "Exchange direction is disabled");
    }

    long converted = (long) Math.floor(amount * direction.ratio());
    if (converted <= 0) {
      throw new ServiceException("invalid_ratio", "Exchange ratio results in zero output");
    }

    String outBizId = idempotencyKey + ":out";
    String inBizId = idempotencyKey + ":in";
    return databaseManager.inTransaction(connection -> {
      ensureWallet(connection, userId);
      boolean outApplied = applyDelta(
          connection,
          userId,
          fromCurrency,
          -amount,
          "EXCHANGE_OUT",
          outBizId,
          true);
      if (!outApplied) {
        return readBalance(connection, userId, false);
      }
      applyDelta(connection, userId, toCurrency, converted, "EXCHANGE_IN", inBizId, false);
      return readBalance(connection, userId, false);
    });
  }

  WalletBalance adjustBalance(long userId, CurrencyType currency, long delta, String reason, String bizId) {
    if (currency == null) {
      throw new ServiceException("invalid_currency", "Currency is required");
    }
    if (delta == 0) {
      throw new ServiceException("invalid_amount", "Delta cannot be zero");
    }
    String normalizedReason = reason == null || reason.isBlank() ? "ADMIN_ADJUST" : reason.trim();
    return databaseManager.inTransaction(connection -> {
      ensureWallet(connection, userId);
      boolean applied = applyDelta(
          connection,
          userId,
          currency,
          delta,
          normalizedReason,
          bizId,
          delta < 0);
      if (!applied) {
        return readBalance(connection, userId, false);
      }
      return readBalance(connection, userId, false);
    });
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Column name comes from enum and cannot be user controlled")
  boolean applyDelta(
      Connection connection,
      long userId,
      CurrencyType currency,
      long delta,
      String bizType,
      String bizId,
      boolean enforceBalance) throws SQLException {
    ensureWallet(connection, userId);
    long walletId = readWalletId(connection, userId, true);

    if (!insertLedger(connection, walletId, currency, delta, bizType, bizId)) {
      return false;
    }

    if (currency == CurrencyType.GAME_COIN && isGameCoinBackedByVault()) {
      GameCoinAccount account = readGameCoinAccount(connection, userId, true);
      long gameCoin = applyVaultDelta(account, delta, enforceBalance);
      updateGameCoinMirror(connection, walletId, gameCoin);
      return true;
    }

    String column = currency.columnName();
    String sql = "UPDATE wallets SET " + column + " = " + column + " + ? WHERE id = ?";
    if (enforceBalance) {
      sql += " AND " + column + " + ? >= 0";
    }

    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, delta);
      statement.setLong(2, walletId);
      if (enforceBalance) {
        statement.setLong(3, delta);
      }
      int updated = statement.executeUpdate();
      if (updated == 0) {
        throw new ServiceException("insufficient_funds", "Wallet balance is insufficient");
      }
    }

    return true;
  }

  private long applyVaultDelta(GameCoinAccount account, long delta, boolean enforceBalance) {
    Economy economy = this.vaultEconomy;
    if (economy == null) {
      throw new ServiceException("vault_unavailable", "Vault economy provider is unavailable");
    }

    String accountName = account.accountName();
    double currentBalance = economy.getBalance(accountName);
    if (enforceBalance && currentBalance + delta < 0.0D) {
      throw new ServiceException("insufficient_funds", "Wallet balance is insufficient");
    }

    EconomyResponse response;
    if (delta >= 0L) {
      response = economy.depositPlayer(accountName, delta);
    } else {
      response = economy.withdrawPlayer(accountName, -delta);
    }
    if (!response.transactionSuccess()) {
      String message = response.errorMessage == null || response.errorMessage.isBlank()
          ? "Vault transaction failed"
          : response.errorMessage;
      throw new ServiceException("vault_error", message);
    }
    return toCoins(economy.getBalance(accountName));
  }

  private long readVaultBalance(GameCoinAccount account) {
    Economy economy = this.vaultEconomy;
    if (economy == null) {
      throw new ServiceException("vault_unavailable", "Vault economy provider is unavailable");
    }
    return toCoins(economy.getBalance(account.accountName()));
  }

  private long toCoins(double value) {
    if (!Double.isFinite(value) || value <= 0.0D) {
      return 0L;
    }
    double floor = Math.floor(value);
    if (floor >= Long.MAX_VALUE) {
      return Long.MAX_VALUE;
    }
    return (long) floor;
  }

  private boolean isGameCoinBackedByVault() {
    return this.vaultEconomy != null;
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Lock clause is selected from a fixed boolean branch")
  private GameCoinAccount readGameCoinAccount(Connection connection, long userId, boolean forUpdate)
      throws SQLException {
    String lock = forUpdate ? sqlProvider.forUpdateClause() : "";
    String sql = """
        SELECT username, bound_uuid
        FROM web_users
        WHERE id = ?
        """ + lock;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("user_missing", "User not found");
        }
        String username = resultSet.getString("username");
        String boundUuid = resultSet.getString("bound_uuid");
        String accountName = username;
        if (accountName == null || accountName.isBlank()) {
          accountName = boundUuid;
        }
        if (accountName == null || accountName.isBlank()) {
          throw new ServiceException("not_bound", "Minecraft account is not bound yet");
        }
        return new GameCoinAccount(accountName, username, boundUuid);
      }
    }
  }

  private boolean insertLedger(
      Connection connection,
      long walletId,
      CurrencyType currency,
      long delta,
      String bizType,
      String bizId) throws SQLException {
    String sql = sqlProvider.insertWalletLedgerIfAbsentSql();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, walletId);
      statement.setString(2, currency.name());
      statement.setLong(3, delta);
      statement.setString(4, bizType);
      statement.setString(5, bizId);
      return statement.executeUpdate() == 1;
    }
  }

  private void ensureWallet(Connection connection, long userId) throws SQLException {
    String sql = sqlProvider.insertWalletIfMissingSql();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.executeUpdate();
    }
  }

  private void updateGameCoinMirror(Connection connection, long walletId, long gameCoin) throws SQLException {
    String sql = "UPDATE wallets SET game_coin = ? WHERE id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, gameCoin);
      statement.setLong(2, walletId);
      statement.executeUpdate();
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Lock clause is selected from a fixed boolean branch")
  private long readWalletId(Connection connection, long userId, boolean forUpdate) throws SQLException {
    String lockClause = forUpdate ? sqlProvider.forUpdateClause() : "";
    String sql = "SELECT id FROM wallets WHERE user_id = ?" + lockClause;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("wallet_missing", "Wallet does not exist");
        }
        return resultSet.getLong("id");
      }
    }
  }

  private WalletBalance readBalance(Connection connection, long userId, boolean forUpdate)
      throws SQLException {
    RawWalletBalance raw = readRawBalance(connection, userId, forUpdate);
    if (!isGameCoinBackedByVault()) {
      return new WalletBalance(raw.shopCoin(), raw.gameCoin());
    }
    GameCoinAccount account = readGameCoinAccount(connection, userId, forUpdate);
    long gameCoin = readVaultBalance(account);
    if (raw.gameCoin() != gameCoin) {
      updateGameCoinMirror(connection, raw.walletId(), gameCoin);
    }
    return new WalletBalance(raw.shopCoin(), gameCoin);
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Lock clause is selected from a fixed boolean branch")
  private RawWalletBalance readRawBalance(Connection connection, long userId, boolean forUpdate)
      throws SQLException {
    String lockClause = forUpdate ? sqlProvider.forUpdateClause() : "";
    String sql = "SELECT id, shop_coin, game_coin FROM wallets WHERE user_id = ?" + lockClause;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("wallet_missing", "Wallet does not exist");
        }
        return new RawWalletBalance(
            resultSet.getLong("id"),
            resultSet.getLong("shop_coin"),
            resultSet.getLong("game_coin"));
      }
    }
  }

  record WalletBalance(long shopCoin, long gameCoin) {
  }

  record GameCoinIntegrationStatus(
      boolean vaultPluginPresent,
      boolean hooked,
      String provider,
      boolean gameCoinBackedByVault) {
  }

  private record GameCoinAccount(String accountName, String username, String boundUuid) {
  }

  private record RawWalletBalance(long walletId, long shopCoin, long gameCoin) {
  }

  record LedgerEntry(
      CurrencyType currency,
      long delta,
      String bizType,
      String bizId,
      java.time.LocalDateTime createdAt) {
  }
}
