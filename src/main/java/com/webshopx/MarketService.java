package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

class MarketService {
  private static final int DEFAULT_LIMIT = 100;

  private final DatabaseManager databaseManager;
  private final WalletService walletService;
  private final ItemSnapshotCodec itemSnapshotCodec;

  MarketService(DatabaseManager databaseManager, WalletService walletService) {
    this.databaseManager = databaseManager;
    this.walletService = walletService;
    this.itemSnapshotCodec = new ItemSnapshotCodec();
  }

  ListingCreateResult createListingFromPlayer(
      Player player,
      long price,
      int amount,
      CurrencyType currency) {
    if (price <= 0L) {
      throw new ServiceException("invalid_price", "Price must be positive");
    }
    if (amount <= 0 || amount > 64) {
      throw new ServiceException("invalid_amount", "Amount must be between 1 and 64");
    }

    ItemStack handItem = player.getInventory().getItemInMainHand();
    if (handItem.getType() == Material.AIR) {
      throw new ServiceException("empty_hand", "Main hand item is empty");
    }
    if (handItem.getAmount() < amount) {
      throw new ServiceException("insufficient_item", "Not enough items in main hand");
    }

    ItemStack listingItem = handItem.clone();
    listingItem.setAmount(amount);
    ItemSnapshotCodec.Snapshot snapshot = itemSnapshotCodec.serialize(listingItem);
    BoundUser boundUser = databaseManager.withConnection(connection ->
        readBoundUserByUuid(connection, player.getUniqueId(), false));
    if (boundUser == null) {
      throw new ServiceException("not_bound", "Please bind your web account before listing items");
    }

    removeFromMainHand(player, amount);
    try {
      long listingId = databaseManager.inTransaction(connection -> createListingInTransaction(
          connection,
          boundUser,
          currency,
          price,
          listingItem,
          snapshot));
      return new ListingCreateResult(listingId, listingItem.getType().name(), amount, currency, price);
    } catch (Exception exception) {
      restoreItem(player, listingItem);
      throw exception;
    }
  }

  List<ListingView> listActiveListings(int requestedLimit) {
    int limit = normalizeLimit(requestedLimit);
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT ml.id, ml.seller_user_id, u.username AS seller_name, ml.seller_uuid, ml.currency, ml.price,
                 ml.quantity, ml.item_material, ml.item_meta_json, ml.status, ml.created_at
          FROM market_listings ml
          JOIN web_users u ON u.id = ml.seller_user_id
          WHERE ml.status = 'ACTIVE'
          ORDER BY ml.id DESC
          LIMIT ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setInt(1, limit);
        return readListingViews(statement.executeQuery());
      }
    });
  }

  List<ListingView> listOwnListings(long userId, int requestedLimit) {
    int limit = normalizeLimit(requestedLimit);
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT ml.id, ml.seller_user_id, u.username AS seller_name, ml.seller_uuid, ml.currency, ml.price,
                 ml.quantity, ml.item_material, ml.item_meta_json, ml.status, ml.created_at
          FROM market_listings ml
          JOIN web_users u ON u.id = ml.seller_user_id
          WHERE ml.seller_user_id = ?
          ORDER BY ml.id DESC
          LIMIT ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        statement.setInt(2, limit);
        return readListingViews(statement.executeQuery());
      }
    });
  }

  TradeResult buyListing(long buyerUserId, long listingId, String idempotencyKey) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    String normalizedIdempotency = normalizeIdempotencyKey(idempotencyKey);
    return databaseManager.inTransaction(connection ->
        buyListingInTransaction(connection, buyerUserId, listingId, normalizedIdempotency));
  }

  UnlistResult unlist(long sellerUserId, long listingId) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    return databaseManager.inTransaction(connection ->
        unlistInTransaction(connection, sellerUserId, listingId));
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Lock clause is selected from a fixed boolean branch")
  private BoundUser readBoundUserByUuid(Connection connection, UUID playerUuid, boolean forUpdate)
      throws SQLException {
    String lock = forUpdate ? " FOR UPDATE" : "";
    String sql = """
        SELECT id, username, bound_uuid
        FROM web_users
        WHERE bound_uuid = ?
        """ + lock;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, playerUuid.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return new BoundUser(
            resultSet.getLong("id"),
            resultSet.getString("username"),
            UUID.fromString(resultSet.getString("bound_uuid")));
      }
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Lock clause is selected from a fixed boolean branch")
  private BoundUser readBoundUserById(Connection connection, long userId, boolean forUpdate)
      throws SQLException {
    String lock = forUpdate ? " FOR UPDATE" : "";
    String sql = """
        SELECT id, username, bound_uuid
        FROM web_users
        WHERE id = ?
        """ + lock;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("user_missing", "Web user not found");
        }
        String boundUuid = resultSet.getString("bound_uuid");
        if (boundUuid == null || boundUuid.isBlank()) {
          throw new ServiceException("not_bound", "Minecraft account is not bound");
        }
        return new BoundUser(
            resultSet.getLong("id"),
            resultSet.getString("username"),
            UUID.fromString(boundUuid));
      }
    }
  }

  private long createListingInTransaction(
      Connection connection,
      BoundUser seller,
      CurrencyType currency,
      long price,
      ItemStack listingItem,
      ItemSnapshotCodec.Snapshot snapshot) throws SQLException {
    String sql = """
        INSERT INTO market_listings (
          seller_user_id, seller_uuid, currency, price, quantity, item_material, raw_item_blob,
          item_meta_json, item_hash, status
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
        """;
    try (PreparedStatement statement =
             connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, seller.userId());
      statement.setString(2, seller.boundUuid().toString());
      statement.setString(3, currency.name());
      statement.setLong(4, price);
      statement.setInt(5, listingItem.getAmount());
      statement.setString(6, listingItem.getType().name());
      statement.setBytes(7, snapshot.rawItemBlob());
      statement.setString(8, snapshot.itemMetaJson());
      statement.setString(9, snapshot.itemHash());
      statement.executeUpdate();
      try (ResultSet keyResult = statement.getGeneratedKeys()) {
        if (!keyResult.next()) {
          throw new IllegalStateException("Could not read generated market listing id");
        }
        return keyResult.getLong(1);
      }
    }
  }

  private TradeResult buyListingInTransaction(
      Connection connection,
      long buyerUserId,
      long listingId,
      String idempotencyKey) throws SQLException {
    ExistingTrade existingTrade = readExistingTrade(connection, buyerUserId, idempotencyKey);
    if (existingTrade != null) {
      return new TradeResult(
          TradeState.EXISTING,
          existingTrade.tradeId(),
          existingTrade.listingId(),
          CurrencyType.valueOf(existingTrade.currency()),
          existingTrade.totalPrice());
    }

    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!listing.status().equals("ACTIVE")) {
      throw new ServiceException("listing_unavailable", "Listing is no longer active");
    }
    if (listing.sellerUserId() == buyerUserId) {
      throw new ServiceException("invalid_trade", "You cannot buy your own listing");
    }

    BoundUser buyer = readBoundUserById(connection, buyerUserId, true);
    String buyerDebitBizId = "mkt-buy:" + buyer.userId() + ":" + idempotencyKey;
    String sellerCreditBizId = "mkt-sell:" + listing.id();

    walletService.applyDelta(
        connection,
        buyer.userId(),
        listing.currency(),
        -listing.price(),
        "MARKET_BUY",
        buyerDebitBizId,
        true);
    walletService.applyDelta(
        connection,
        listing.sellerUserId(),
        listing.currency(),
        listing.price(),
        "MARKET_SELL",
        sellerCreditBizId,
        false);

    long tradeId = insertTrade(
        connection,
        listing.id(),
        buyer.userId(),
        listing.sellerUserId(),
        listing.currency(),
        listing.price(),
        idempotencyKey);
    updateListingToSold(connection, listing.id(), buyer);
    enqueueMarketItemDelivery(
        connection,
        listing.id(),
        buyer.userId(),
        buyer.boundUuid(),
        listing.rawItemBlob(),
        listing.quantity(),
        DeliveryType.SALE);
    return new TradeResult(
        TradeState.CREATED,
        tradeId,
        listing.id(),
        listing.currency(),
        listing.price());
  }

  private UnlistResult unlistInTransaction(Connection connection, long sellerUserId, long listingId)
      throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!listing.status().equals("ACTIVE")) {
      throw new ServiceException("listing_unavailable", "Listing is no longer active");
    }
    if (listing.sellerUserId() != sellerUserId) {
      throw new ServiceException("forbidden", "Only the owner can unlist this listing");
    }
    BoundUser seller = readBoundUserById(connection, sellerUserId, true);

    String updateSql = """
        UPDATE market_listings
        SET status = 'UNLISTED', unlisted_at = NOW()
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
      statement.setLong(1, listing.id());
      statement.executeUpdate();
    }

    enqueueMarketItemDelivery(
        connection,
        listing.id(),
        seller.userId(),
        seller.boundUuid(),
        listing.rawItemBlob(),
        listing.quantity(),
        DeliveryType.UNLIST);
    return new UnlistResult(listing.id(), listing.currency(), listing.price(), listing.quantity());
  }

  private ExistingTrade readExistingTrade(Connection connection, long buyerUserId, String idempotencyKey)
      throws SQLException {
    String sql = """
        SELECT t.id, t.listing_id, t.currency, t.total_price
        FROM market_trades t
        WHERE t.buyer_user_id = ? AND t.idempotency_key = ?
        FOR UPDATE
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, buyerUserId);
      statement.setString(2, idempotencyKey);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return new ExistingTrade(
            resultSet.getLong("id"),
            resultSet.getLong("listing_id"),
            resultSet.getString("currency"),
            resultSet.getLong("total_price"));
      }
    }
  }

  private long insertTrade(
      Connection connection,
      long listingId,
      long buyerUserId,
      long sellerUserId,
      CurrencyType currency,
      long totalPrice,
      String idempotencyKey) throws SQLException {
    String sql = """
        INSERT INTO market_trades (
          listing_id, buyer_user_id, seller_user_id, currency, total_price, idempotency_key
        )
        VALUES (?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement =
             connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, listingId);
      statement.setLong(2, buyerUserId);
      statement.setLong(3, sellerUserId);
      statement.setString(4, currency.name());
      statement.setLong(5, totalPrice);
      statement.setString(6, idempotencyKey);
      statement.executeUpdate();
      try (ResultSet keyResult = statement.getGeneratedKeys()) {
        if (!keyResult.next()) {
          throw new IllegalStateException("Could not read generated market trade id");
        }
        return keyResult.getLong(1);
      }
    }
  }

  private void updateListingToSold(Connection connection, long listingId, BoundUser buyer)
      throws SQLException {
    String sql = """
        UPDATE market_listings
        SET status = 'SOLD', buyer_user_id = ?, buyer_uuid = ?, sold_at = NOW()
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, buyer.userId());
      statement.setString(2, buyer.boundUuid().toString());
      statement.setLong(3, listingId);
      statement.executeUpdate();
    }
  }

  private void enqueueMarketItemDelivery(
      Connection connection,
      long listingId,
      long targetUserId,
      UUID targetUuid,
      byte[] itemBlob,
      int quantity,
      DeliveryType deliveryType) throws SQLException {
    String sql = """
        INSERT INTO market_item_deliveries (
          listing_id, target_user_id, target_uuid, item_blob, quantity, delivery_type, status, next_retry_at
        )
        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, listingId);
      statement.setLong(2, targetUserId);
      statement.setString(3, targetUuid.toString());
      statement.setBytes(4, itemBlob);
      statement.setInt(5, quantity);
      statement.setString(6, deliveryType.name());
      statement.setTimestamp(7, Timestamp.valueOf(LocalDateTime.now()));
      statement.executeUpdate();
    }
  }

  private MarketListing readListingForUpdate(Connection connection, long listingId) throws SQLException {
    String sql = """
        SELECT id, seller_user_id, seller_uuid, currency, price, quantity, item_material, raw_item_blob,
               item_meta_json, item_hash, status
        FROM market_listings
        WHERE id = ?
        FOR UPDATE
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, listingId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("listing_missing", "Market listing does not exist");
        }
        return new MarketListing(
            resultSet.getLong("id"),
            resultSet.getLong("seller_user_id"),
            UUID.fromString(resultSet.getString("seller_uuid")),
            CurrencyType.valueOf(resultSet.getString("currency")),
            resultSet.getLong("price"),
            resultSet.getInt("quantity"),
            resultSet.getString("item_material"),
            resultSet.getBytes("raw_item_blob"),
            resultSet.getString("item_meta_json"),
            resultSet.getString("item_hash"),
            resultSet.getString("status"));
      }
    }
  }

  private List<ListingView> readListingViews(ResultSet resultSet) throws SQLException {
    List<ListingView> listings = new ArrayList<>();
    while (resultSet.next()) {
      listings.add(new ListingView(
          resultSet.getLong("id"),
          resultSet.getLong("seller_user_id"),
          resultSet.getString("seller_name"),
          UUID.fromString(resultSet.getString("seller_uuid")),
          CurrencyType.valueOf(resultSet.getString("currency")),
          resultSet.getLong("price"),
          resultSet.getInt("quantity"),
          resultSet.getString("item_material"),
          resultSet.getString("item_meta_json"),
          resultSet.getString("status"),
          resultSet.getTimestamp("created_at").toLocalDateTime()));
    }
    return listings;
  }

  private String normalizeIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ServiceException("invalid_idempotency", "Idempotency key is required");
    }
    String normalized = idempotencyKey.trim();
    if (normalized.length() > 96) {
      throw new ServiceException("invalid_idempotency", "Idempotency key exceeds 96 characters");
    }
    return normalized;
  }

  private int normalizeLimit(int limit) {
    if (limit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, 200);
  }

  private void removeFromMainHand(Player player, int amount) {
    ItemStack current = player.getInventory().getItemInMainHand();
    if (current.getAmount() == amount) {
      player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
      return;
    }
    current.setAmount(current.getAmount() - amount);
    player.getInventory().setItemInMainHand(current);
  }

  private void restoreItem(Player player, ItemStack itemStack) {
    player.getInventory().addItem(itemStack)
        .values()
        .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
  }

  private record BoundUser(long userId, String username, UUID boundUuid) {
  }

  private record ExistingTrade(long tradeId, long listingId, String currency, long totalPrice) {
  }

  private record MarketListing(
      long id,
      long sellerUserId,
      UUID sellerUuid,
      CurrencyType currency,
      long price,
      int quantity,
      String itemMaterial,
      byte[] rawItemBlob,
      String itemMetaJson,
      String itemHash,
      String status) {
  }

  enum TradeState {
    CREATED,
    EXISTING
  }

  enum DeliveryType {
    SALE,
    UNLIST
  }

  record ListingCreateResult(
      long listingId,
      String material,
      int quantity,
      CurrencyType currency,
      long price) {
  }

  record ListingView(
      long id,
      long sellerUserId,
      String sellerName,
      UUID sellerUuid,
      CurrencyType currency,
      long price,
      int quantity,
      String itemMaterial,
      String itemMetaJson,
      String status,
      LocalDateTime createdAt) {
  }

  record TradeResult(
      TradeState state,
      long tradeId,
      long listingId,
      CurrencyType currency,
      long totalPrice) {
  }

  record UnlistResult(long listingId, CurrencyType currency, long price, int quantity) {
  }
}
