package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class WalletService {
  private final DatabaseManager databaseManager;
  private final SqlProvider sqlProvider;
  private final Supplier<ExchangePolicy> settingsSupplier;
  private final GameCoinProvider gameCoinProvider;
  private final BusinessLedgerSink businessLedgerSink;

  public WalletService(
      DatabaseManager databaseManager,
      Supplier<ExchangePolicy> settingsSupplier,
      GameCoinProvider gameCoinProvider,
      BusinessLedgerSink businessLedgerSink) {
    this.databaseManager = databaseManager;
    this.sqlProvider = databaseManager.sqlProvider();
    this.settingsSupplier = settingsSupplier;
    this.gameCoinProvider = gameCoinProvider == null ? GameCoinProvider.unavailable() : gameCoinProvider;
    this.businessLedgerSink = businessLedgerSink;
    refreshVaultHook();
  }

  public void refreshVaultHook() {
    gameCoinProvider.refresh();
  }

  public GameCoinIntegrationStatus getGameCoinIntegrationStatus() {
    refreshVaultHook();
    GameCoinProvider.IntegrationStatus status = gameCoinProvider.status();
    return new GameCoinIntegrationStatus(
        status.pluginPresent(), status.available(), status.provider(), status.available());
  }

  public WalletBalance getBalance(long userId) {
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

  public List<LedgerEntry> listRecentLedger(long userId, int limit) {
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

  public WalletBalance exchange(
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

    ExchangeDirection direction = settingsSupplier.get().direction(fromCurrency, toCurrency);
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

  public WalletBalance adjustBalance(long userId, CurrencyType currency, long delta, String reason, String bizId) {
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
  public boolean applyDelta(
      Connection connection,
      long userId,
      CurrencyType currency,
      long delta,
      String bizType,
      String bizId,
      boolean enforceBalance) throws SQLException {
    ensureWallet(connection, userId);
    long walletId = readWalletId(connection, userId, true);
    String username = readUsername(connection, userId, false);

    if (!insertLedger(connection, walletId, currency, delta, bizType, bizId)) {
      return false;
    }

    if (currency == CurrencyType.GAME_COIN && isGameCoinBackedByVault()) {
      GameCoinAccount account = readGameCoinAccount(connection, userId, true);
      long gameCoin = applyVaultDelta(account, delta, enforceBalance);
      updateGameCoinMirror(connection, walletId, gameCoin);
      LedgerBusinessContext context = resolveLedgerBusinessContext(connection, bizType, bizId);
      logBusinessLedger(userId, username, walletId, currency, delta, bizType, bizId, context, enforceBalance);
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

    LedgerBusinessContext context = resolveLedgerBusinessContext(connection, bizType, bizId);
    logBusinessLedger(userId, username, walletId, currency, delta, bizType, bizId, context, enforceBalance);
    return true;
  }

  private void logBusinessLedger(
      long userId,
      String username,
      long walletId,
      CurrencyType currency,
      long delta,
      String bizType,
      String bizId,
      LedgerBusinessContext context,
      boolean enforceBalance) {
    if (businessLedgerSink == null) {
      return;
    }
    businessLedgerSink.logWalletLedger(
        userId,
        username,
        walletId,
        currency,
        delta,
        bizType,
        bizId,
        context.tradeType(),
        context.itemDetail(),
        enforceBalance,
        isGameCoinBackedByVault());
  }

  private LedgerBusinessContext resolveLedgerBusinessContext(
      Connection connection,
      String bizType,
      String bizId) {
    String type = bizType == null ? "" : bizType.trim().toUpperCase(java.util.Locale.ROOT);
    try {
      if (type.startsWith("ORDER_") || type.startsWith("RECYCLE_")) {
        return readOrderContext(connection, bizId, type);
      }
      if (type.startsWith("MARKET_")) {
        return readMarketContext(connection, bizId, type);
      }
    } catch (Exception ignored) {
      // Fall through to default context for logging robustness.
    }
    if (type.startsWith("EXCHANGE_")) {
      return new LedgerBusinessContext("System Exchange", "-");
    }
    if ("REDEEM_CODE".equals(type)) {
      return new LedgerBusinessContext("Official Shop|Redeem Code", "-");
    }
    return new LedgerBusinessContext("-", "-");
  }

  private LedgerBusinessContext readOrderContext(
      Connection connection,
      String orderNo,
      String bizType) throws SQLException {
    if (orderNo == null || orderNo.isBlank()) {
      return new LedgerBusinessContext("Official Shop", "-");
    }
    String sql = """
        SELECT p.product_type, p.item_material, p.title, oi.quantity
        FROM orders o
        JOIN order_items oi ON oi.order_id = o.id
        JOIN products p ON p.id = oi.product_id
        WHERE o.order_no = ?
        ORDER BY oi.id ASC
        LIMIT 1
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, orderNo);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return new LedgerBusinessContext("Official Shop", "-");
        }
        String productType = resultSet.getString("product_type");
        String material = resultSet.getString("item_material");
        String title = resultSet.getString("title");
        int quantity = resultSet.getInt("quantity");
        String tradeType = "Official Shop";
        if ((productType != null && productType.toUpperCase(java.util.Locale.ROOT).startsWith("RECYCLE_"))
            || bizType.startsWith("RECYCLE_")) {
          tradeType = "Official Shop|Recycle";
        } else if ("GROUP_BUY_VOUCHER".equalsIgnoreCase(productType)) {
          tradeType = "Official Shop|Group Buy Voucher";
        } else {
          tradeType = "Official Shop|Sale";
        }
        String item = formatItemDetail(material, title, quantity);
        return new LedgerBusinessContext(tradeType, item);
      }
    }
  }

  private LedgerBusinessContext readMarketContext(
      Connection connection,
      String bizId,
      String bizType) throws SQLException {
    Long listingId = parseListingIdFromBizId(bizId);
    Long tradeId = parseTradeIdFromBizId(bizId);
    if (listingId == null && tradeId == null) {
      return new LedgerBusinessContext(resolveMarketTypeLabel(bizType, null), "-");
    }

    String sqlByTrade = """
        SELECT ml.trade_mode, ml.item_material, mt.quantity
        FROM market_trades mt
        JOIN market_listings ml ON ml.id = mt.listing_id
        WHERE mt.id = ?
        LIMIT 1
        """;
    String sqlByListing = """
        SELECT trade_mode, item_material, quantity
        FROM market_listings
        WHERE id = ?
        LIMIT 1
        """;

    if (tradeId != null) {
      try (PreparedStatement statement = connection.prepareStatement(sqlByTrade)) {
        statement.setLong(1, tradeId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            String mode = resultSet.getString("trade_mode");
            String material = resultSet.getString("item_material");
            int quantity = resultSet.getInt("quantity");
            return new LedgerBusinessContext(
                resolveMarketTypeLabel(bizType, mode),
                formatItemDetail(material, null, quantity));
          }
        }
      }
    }

    if (listingId != null) {
      try (PreparedStatement statement = connection.prepareStatement(sqlByListing)) {
        statement.setLong(1, listingId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            String mode = resultSet.getString("trade_mode");
            String material = resultSet.getString("item_material");
            int quantity = resultSet.getInt("quantity");
            return new LedgerBusinessContext(
                resolveMarketTypeLabel(bizType, mode),
                formatItemDetail(material, null, quantity));
          }
        }
      }
    }

    return new LedgerBusinessContext(resolveMarketTypeLabel(bizType, null), "-");
  }

  private String resolveMarketTypeLabel(String bizType, String tradeMode) {
    String mode = tradeMode == null ? "" : tradeMode.trim().toUpperCase(java.util.Locale.ROOT);
    if ("AUCTION".equals(mode)
        || "MARKET_BID_HOLD".equals(bizType)
        || "MARKET_BID_REFUND".equals(bizType)) {
      return "Player Market|Auction";
    }
    return "Player Market|Sale";
  }

  private String formatItemDetail(String material, String title, int quantity) {
    String qty = quantity > 0 ? " x" + quantity : "";
    if (material != null && !material.isBlank()) {
      if (title != null && !title.isBlank()) {
        return material.trim() + qty + " (" + title.trim() + ")";
      }
      return material.trim() + qty;
    }
    if (title != null && !title.isBlank()) {
      return title.trim() + qty;
    }
    return "-";
  }

  private Long parseListingIdFromBizId(String bizId) {
    if (bizId == null || bizId.isBlank()) {
      return null;
    }
    String[] parts = bizId.split(":");
    if (parts.length < 2) {
      return null;
    }
    String head = parts[0].toLowerCase(java.util.Locale.ROOT);
    if (!head.equals("mkt-buy-escrow")
        && !head.equals("mkt-create-cost")
        && !head.equals("mkt-bid-hold")
        && !head.equals("mkt-bid-refund")) {
      return null;
    }
    return parsePositiveLong(parts[1]);
  }

  private Long parseTradeIdFromBizId(String bizId) {
    if (bizId == null || bizId.isBlank()) {
      return null;
    }
    String[] parts = bizId.split(":");
    if (parts.length < 2) {
      return null;
    }
    String head = parts[0].toLowerCase(java.util.Locale.ROOT);
    if (head.equals("mkt-sell") || head.equals("mkt-sink")) {
      return parsePositiveLong(parts[1]);
    }
    return null;
  }

  private Long parsePositiveLong(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      long parsed = Long.parseLong(raw.trim());
      return parsed > 0 ? parsed : null;
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private String readUsername(Connection connection, long userId, boolean forUpdate) throws SQLException {
    String lockClause = forUpdate ? sqlProvider.forUpdateClause() : "";
    String sql = "SELECT username FROM web_users WHERE id = ?" + lockClause;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return "unknown";
        }
        String username = resultSet.getString("username");
        if (username == null || username.isBlank()) {
          return "unknown";
        }
        return username;
      }
    }
  }

  private long applyVaultDelta(GameCoinAccount account, long delta, boolean enforceBalance) {
    return gameCoinProvider.apply(account.accountName(), delta, enforceBalance);
  }

  private long readVaultBalance(GameCoinAccount account) {
    return gameCoinProvider.balance(account.accountName());
  }

  private boolean isGameCoinBackedByVault() {
    return gameCoinProvider.status().available();
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

  public record WalletBalance(long shopCoin, long gameCoin) {
  }

  public record GameCoinIntegrationStatus(
      boolean vaultPluginPresent,
      boolean hooked,
      String provider,
      boolean gameCoinBackedByVault) {
  }

  private record GameCoinAccount(String accountName, String username, String boundUuid) {
  }

  private record RawWalletBalance(long walletId, long shopCoin, long gameCoin) {
  }

  public record LedgerEntry(
      CurrencyType currency,
      long delta,
      String bizType,
      String bizId,
      java.time.LocalDateTime createdAt) {
  }

  private record LedgerBusinessContext(String tradeType, String itemDetail) {
  }

  public record ExchangeDirection(boolean enabled, double ratio) { }

  public record ExchangePolicy(ExchangeDirection shopToGame, ExchangeDirection gameToShop) {
    public ExchangePolicy {
      shopToGame = shopToGame == null ? new ExchangeDirection(false, 0.0D) : shopToGame;
      gameToShop = gameToShop == null ? new ExchangeDirection(false, 0.0D) : gameToShop;
    }

    public ExchangeDirection direction(CurrencyType from, CurrencyType to) {
      if (from == CurrencyType.SHOP_COIN && to == CurrencyType.GAME_COIN) return shopToGame;
      if (from == CurrencyType.GAME_COIN && to == CurrencyType.SHOP_COIN) return gameToShop;
      return new ExchangeDirection(false, 0.0D);
    }

    public static ExchangePolicy disabled() {
      return new ExchangePolicy(null, null);
    }
  }

  public interface GameCoinProvider {
    void refresh();
    IntegrationStatus status();
    long balance(String accountName);
    long apply(String accountName, long delta, boolean enforceBalance);

    record IntegrationStatus(boolean pluginPresent, boolean available, String provider) { }

    static GameCoinProvider unavailable() {
      return new GameCoinProvider() {
        public void refresh() { }
        public IntegrationStatus status() { return new IntegrationStatus(false, false, null); }
        public long balance(String accountName) {
          throw new ServiceException("economy_unavailable", "External game-coin provider is unavailable");
        }
        public long apply(String accountName, long delta, boolean enforceBalance) {
          throw new ServiceException("economy_unavailable", "External game-coin provider is unavailable");
        }
      };
    }
  }

  @FunctionalInterface
  public interface BusinessLedgerSink {
    void logWalletLedger(
        long userId, String username, long walletId, CurrencyType currency, long delta,
        String bizType, String bizId, String tradeType, String itemDetail,
        boolean enforceBalance, boolean gameCoinBackedByExternalProvider);
  }
}
