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
import java.util.function.Supplier;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

class MarketService {
  private static final int DEFAULT_LIMIT = 100;

  private final DatabaseManager databaseManager;
  private final WalletService walletService;
  private final Supplier<PluginSettings> settingsSupplier;
  private final ItemSnapshotCodec itemSnapshotCodec;

  MarketService(
      DatabaseManager databaseManager,
      WalletService walletService,
      Supplier<PluginSettings> settingsSupplier) {
    this.databaseManager = databaseManager;
    this.walletService = walletService;
    this.settingsSupplier = settingsSupplier;
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

    int listingLimit = resolveListingLimit(player);

    removeFromMainHand(player, amount);
    try {
      long listingId = databaseManager.inTransaction(connection -> createListingInTransaction(
          connection,
          boundUser,
          currency,
          price,
          listingItem,
          snapshot,
          listingLimit));
      return new ListingCreateResult(listingId, listingItem.getType().name(), amount, currency, price);
    } catch (Exception exception) {
      restoreItem(player, listingItem);
      throw exception;
    }
  }

  List<ListingView> listListings(ListingQuery query) {
    int limit = normalizeLimit(query.limit());
    String sortColumn = resolveSortColumn(query.sort());
    String sortDirection = query.ascending() ? "ASC" : "DESC";

    return databaseManager.withConnection(connection -> {
      StringBuilder sql = new StringBuilder("""
          SELECT ml.id, ml.seller_user_id, u.username AS seller_name, ml.seller_uuid, ml.currency, ml.price,
                 ml.quantity, ml.item_material, ml.item_meta_json, ml.remark, ml.status, ml.created_at
          FROM market_listings ml
          JOIN web_users u ON u.id = ml.seller_user_id
          WHERE 1=1
          """);
      List<Object> params = new ArrayList<>();

      if (query.activeOnly()) {
        sql.append(" AND ml.status = 'ACTIVE'");
      }
      if (query.sellerUserId() != null) {
        sql.append(" AND ml.seller_user_id = ?");
        params.add(query.sellerUserId());
      }
      if (query.currency() != null) {
        sql.append(" AND ml.currency = ?");
        params.add(query.currency().name());
      }
      if (query.material() != null && !query.material().isBlank()) {
        sql.append(" AND ml.item_material = ?");
        params.add(query.material());
      }
      if (query.minPrice() != null) {
        sql.append(" AND ml.price >= ?");
        params.add(query.minPrice());
      }
      if (query.maxPrice() != null) {
        sql.append(" AND ml.price <= ?");
        params.add(query.maxPrice());
      }
      if (query.keyword() != null && !query.keyword().isBlank()) {
        sql.append(" AND (LOWER(ml.item_material) LIKE ? OR LOWER(u.username) LIKE ? OR LOWER(ml.remark) LIKE ?)");
        String keyword = "%" + query.keyword().toLowerCase(Locale.ROOT) + "%";
        params.add(keyword);
        params.add(keyword);
        params.add(keyword);
      }

      sql.append(" ORDER BY ").append(sortColumn).append(" ").append(sortDirection);
      sql.append(" LIMIT ?");
      params.add(limit);

      try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
        for (int i = 0; i < params.size(); i++) {
          statement.setObject(i + 1, params.get(i));
        }
        return readListingViews(statement.executeQuery());
      }
    });
  }

  List<AdminListingView> listAllListings(String statusFilter, int requestedLimit) {
    int limit = normalizeLimit(requestedLimit);
    return databaseManager.withConnection(connection -> {
      String filter = statusFilter == null || statusFilter.isBlank()
          ? ""
          : "WHERE ml.status = ?";
      String sql = """
          SELECT ml.id, ml.seller_user_id, us.username AS seller_name, ml.seller_uuid,
                 ml.buyer_user_id, ub.username AS buyer_name, ml.buyer_uuid,
                 ml.currency, ml.price, ml.quantity, ml.item_material, ml.item_meta_json,
                 ml.remark, ml.status, ml.created_at, ml.sold_at, ml.unlisted_at
          FROM market_listings ml
          JOIN web_users us ON us.id = ml.seller_user_id
          LEFT JOIN web_users ub ON ub.id = ml.buyer_user_id
          """ + filter + " ORDER BY ml.id DESC LIMIT ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        int index = 1;
        if (!filter.isBlank()) {
          statement.setString(index++, statusFilter.trim().toUpperCase(Locale.ROOT));
        }
        statement.setInt(index, limit);
        return readAdminListingViews(statement.executeQuery());
      }
    });
  }

  UnlistResult adminUnlist(long listingId) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    return databaseManager.inTransaction(connection -> adminUnlistInTransaction(connection, listingId));
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

  ListingPriceUpdateResult updateListingPrice(long sellerUserId, long listingId, long newPrice) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    if (newPrice <= 0L) {
      throw new ServiceException("invalid_price", "Price must be positive");
    }
    return databaseManager.inTransaction(connection -> updateListingPriceInTransaction(
        connection,
        sellerUserId,
        listingId,
        newPrice));
  }

  ListingRemarkUpdateResult updateListingRemark(long sellerUserId, long listingId, String remark) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    String normalizedRemark = normalizeRemark(remark);
    return databaseManager.inTransaction(connection -> updateListingRemarkInTransaction(
        connection,
        sellerUserId,
        listingId,
        normalizedRemark));
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
      ItemSnapshotCodec.Snapshot snapshot,
      int listingLimit) throws SQLException {
    int activeListings = countActiveListings(connection, seller.userId());
    if (activeListings >= listingLimit) {
      throw new ServiceException(
          "listing_limit",
          "当前上架数量已达上限 (" + listingLimit + ")");
    }

    String sql = """
        INSERT INTO market_listings (
          seller_user_id, seller_uuid, currency, price, quantity, item_material, raw_item_blob,
          item_meta_json, remark, item_hash, status
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
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
      statement.setString(9, null);
      statement.setString(10, snapshot.itemHash());
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
    int cooldownSeconds = normalizedOrderCooldownSeconds();
    ExistingTrade existingTrade = readExistingTrade(connection, buyerUserId, idempotencyKey);
    if (existingTrade != null) {
      long buyerTotal = existingTrade.buyerTotal() > 0
          ? existingTrade.buyerTotal()
          : existingTrade.totalPrice();
      long sellerReceive = existingTrade.sellerReceive() > 0
          ? existingTrade.sellerReceive()
          : existingTrade.totalPrice();
      return new TradeResult(
          TradeState.EXISTING,
          existingTrade.tradeId(),
          existingTrade.listingId(),
          CurrencyType.valueOf(existingTrade.currency()),
          existingTrade.totalPrice(),
          buyerTotal,
          sellerReceive,
          existingTrade.feeAmount(),
          existingTrade.taxAmount(),
          existingTrade.status(),
          existingTrade.refundDeadline(),
          cooldownSeconds);
    }

    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!listing.status().equals("ACTIVE")) {
      throw new ServiceException("listing_unavailable", "Listing is no longer active");
    }
    if (listing.sellerUserId() == buyerUserId) {
      throw new ServiceException("invalid_trade", "You cannot buy your own listing");
    }

    BoundUser buyer = readBoundUserById(connection, buyerUserId, true);
    PluginSettings.MarketEconomySettings marketEconomy = settingsSupplier.get().economySettings().marketSettings();
    long fee = calculatePercent(listing.price(), marketEconomy.tradeFeePercent());
    long tax = calculatePercent(listing.price(), marketEconomy.tradeTaxPercent());
    long buyerTotal = Math.addExact(listing.price(), tax);
    long sellerReceive = Math.max(0L, listing.price() - fee);
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime refundDeadline = cooldownSeconds > 0
        ? now.plusSeconds(cooldownSeconds)
        : null;
    String tradeStatus = "PENDING";

    String buyerDebitBizId = "mkt-buy:" + buyer.userId() + ":" + idempotencyKey;

    walletService.applyDelta(
        connection,
        buyer.userId(),
        listing.currency(),
        -buyerTotal,
        "MARKET_BUY",
        buyerDebitBizId,
        true);

    long tradeId = insertTrade(
        connection,
        listing.id(),
        buyer.userId(),
        listing.sellerUserId(),
        listing.currency(),
        listing.price(),
        buyerTotal,
        sellerReceive,
        fee,
        tax,
        idempotencyKey,
        tradeStatus,
        refundDeadline);
    updateListingToSold(connection, listing.id(), buyer);
    LocalDateTime deliveryAt = refundDeadline == null ? now : refundDeadline;
    enqueueMarketItemDelivery(
        connection,
        listing.id(),
        buyer.userId(),
        buyer.boundUuid(),
        listing.rawItemBlob(),
        listing.quantity(),
        DeliveryType.SALE,
        deliveryAt);
    return new TradeResult(
        TradeState.CREATED,
        tradeId,
        listing.id(),
        listing.currency(),
        listing.price(),
        buyerTotal,
        sellerReceive,
        fee,
        tax,
        tradeStatus,
        refundDeadline,
        cooldownSeconds);
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
        DeliveryType.UNLIST,
        LocalDateTime.now());
    return new UnlistResult(listing.id(), listing.currency(), listing.price(), listing.quantity());
  }

  private ListingPriceUpdateResult updateListingPriceInTransaction(
      Connection connection,
      long sellerUserId,
      long listingId,
      long newPrice) throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!listing.status().equals("ACTIVE")) {
      throw new ServiceException("listing_unavailable", "Listing is no longer active");
    }
    if (listing.sellerUserId() != sellerUserId) {
      throw new ServiceException("forbidden", "Only the owner can update listing price");
    }

    String sql = """
        UPDATE market_listings
        SET price = ?
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, newPrice);
      statement.setLong(2, listingId);
      statement.executeUpdate();
    }
    return new ListingPriceUpdateResult(listingId, listing.currency(), newPrice);
  }

  private ListingRemarkUpdateResult updateListingRemarkInTransaction(
      Connection connection,
      long sellerUserId,
      long listingId,
      String remark) throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!listing.status().equals("ACTIVE")) {
      throw new ServiceException("listing_unavailable", "Listing is no longer active");
    }
    if (listing.sellerUserId() != sellerUserId) {
      throw new ServiceException("forbidden", "Only the owner can update listing remark");
    }

    String sql = """
        UPDATE market_listings
        SET remark = ?
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, remark);
      statement.setLong(2, listingId);
      statement.executeUpdate();
    }
    return new ListingRemarkUpdateResult(listingId, remark);
  }

  private ExistingTrade readExistingTrade(Connection connection, long buyerUserId, String idempotencyKey)
      throws SQLException {
    String sql = """
        SELECT t.id, t.listing_id, t.currency, t.total_price,
               t.buyer_total, t.seller_receive, t.fee_amount, t.tax_amount,
               t.status, t.refund_deadline
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
            resultSet.getLong("total_price"),
            resultSet.getLong("buyer_total"),
            resultSet.getLong("seller_receive"),
            resultSet.getLong("fee_amount"),
            resultSet.getLong("tax_amount"),
            resultSet.getString("status"),
            resultSet.getTimestamp("refund_deadline") == null
                ? null
                : resultSet.getTimestamp("refund_deadline").toLocalDateTime());
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
      long buyerTotal,
      long sellerReceive,
      long feeAmount,
      long taxAmount,
      String idempotencyKey,
      String status,
      LocalDateTime refundDeadline) throws SQLException {
    String sql = """
        INSERT INTO market_trades (
          listing_id, buyer_user_id, seller_user_id, currency, total_price,
          buyer_total, seller_receive, fee_amount, tax_amount, idempotency_key,
          status, refund_deadline
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement =
             connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, listingId);
      statement.setLong(2, buyerUserId);
      statement.setLong(3, sellerUserId);
      statement.setString(4, currency.name());
      statement.setLong(5, totalPrice);
      statement.setLong(6, buyerTotal);
      statement.setLong(7, sellerReceive);
      statement.setLong(8, feeAmount);
      statement.setLong(9, taxAmount);
      statement.setString(10, idempotencyKey);
      statement.setString(11, status);
      if (refundDeadline == null) {
        statement.setTimestamp(12, null);
      } else {
        statement.setTimestamp(12, Timestamp.valueOf(refundDeadline));
      }
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

  private UnlistResult adminUnlistInTransaction(Connection connection, long listingId)
      throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!listing.status().equals("ACTIVE")) {
      throw new ServiceException("listing_unavailable", "Listing is no longer active");
    }

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
        listing.sellerUserId(),
        listing.sellerUuid(),
        listing.rawItemBlob(),
        listing.quantity(),
        DeliveryType.UNLIST,
        LocalDateTime.now());
    return new UnlistResult(listing.id(), listing.currency(), listing.price(), listing.quantity());
  }

  private void enqueueMarketItemDelivery(
      Connection connection,
      long listingId,
      long targetUserId,
      UUID targetUuid,
      byte[] itemBlob,
      int quantity,
      DeliveryType deliveryType,
      LocalDateTime nextRetryAt) throws SQLException {
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
      statement.setTimestamp(7, Timestamp.valueOf(nextRetryAt));
      statement.executeUpdate();
    }
  }

  private MarketListing readListingForUpdate(Connection connection, long listingId) throws SQLException {
    String sql = """
        SELECT id, seller_user_id, seller_uuid, currency, price, quantity, item_material, raw_item_blob,
               item_meta_json, remark, item_hash, status
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
            resultSet.getString("remark"),
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
          resultSet.getString("remark"),
          resultSet.getString("status"),
          resultSet.getTimestamp("created_at").toLocalDateTime()));
    }
    return listings;
  }

  private List<AdminListingView> readAdminListingViews(ResultSet resultSet) throws SQLException {
    List<AdminListingView> listings = new ArrayList<>();
    while (resultSet.next()) {
      String buyerUuidRaw = resultSet.getString("buyer_uuid");
      UUID buyerUuid = buyerUuidRaw == null ? null : UUID.fromString(buyerUuidRaw);
      listings.add(new AdminListingView(
          resultSet.getLong("id"),
          resultSet.getLong("seller_user_id"),
          resultSet.getString("seller_name"),
          UUID.fromString(resultSet.getString("seller_uuid")),
          resultSet.getObject("buyer_user_id") == null ? null : resultSet.getLong("buyer_user_id"),
          resultSet.getString("buyer_name"),
          buyerUuid,
          CurrencyType.valueOf(resultSet.getString("currency")),
          resultSet.getLong("price"),
          resultSet.getInt("quantity"),
          resultSet.getString("item_material"),
          resultSet.getString("item_meta_json"),
          resultSet.getString("remark"),
          resultSet.getString("status"),
          resultSet.getTimestamp("created_at").toLocalDateTime(),
          resultSet.getTimestamp("sold_at") == null
              ? null
              : resultSet.getTimestamp("sold_at").toLocalDateTime(),
          resultSet.getTimestamp("unlisted_at") == null
              ? null
              : resultSet.getTimestamp("unlisted_at").toLocalDateTime()));
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

  private String normalizeRemark(String rawRemark) {
    if (rawRemark == null) {
      return null;
    }
    String normalized = rawRemark.trim();
    if (normalized.isEmpty()) {
      return null;
    }
    if (normalized.length() > 1000) {
      throw new ServiceException("invalid_remark", "Remark must be <= 1000 chars");
    }
    return normalized;
  }

  private int normalizedOrderCooldownSeconds() {
    int value = settingsSupplier.get().orderCooldownSeconds();
    if (value < 0) {
      return 0;
    }
    return value;
  }

  private long calculatePercent(long baseAmount, double percent) {
    if (percent <= 0) {
      return 0L;
    }
    double normalized = Math.max(0.0, Math.min(100.0, percent));
    double raw = baseAmount * normalized / 100.0;
    if (raw <= 0) {
      return 0L;
    }
    long value = (long) Math.floor(raw);
    if (value < 0) {
      return 0L;
    }
    return Math.min(value, baseAmount);
  }

  private void applyEconomySink(Connection connection, CurrencyType currency, long amount, long listingId)
      throws SQLException {
    PluginSettings.InflationSettings inflation = settingsSupplier.get().economySettings().inflationSettings();
    long treasuryUserId = inflation.treasuryUserId();
    if (inflation.mode() == PluginSettings.InflationMode.TREASURY && treasuryUserId > 0
        && userExists(connection, treasuryUserId)) {
      String sinkBizId = "mkt-sink:" + listingId;
      walletService.applyDelta(
          connection,
          treasuryUserId,
          currency,
          amount,
          "MARKET_SINK",
          sinkBizId,
          false);
    }
  }

  private boolean userExists(Connection connection, long userId) throws SQLException {
    String sql = "SELECT 1 FROM web_users WHERE id = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  private int resolveListingLimit(Player player) {
    int baseLimit = Math.max(1, settingsSupplier.get().marketMaxActiveListings());
    int maxLimit = baseLimit;
    for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
      if (!info.getValue()) {
        continue;
      }
      String permission = info.getPermission();
      if (permission == null) {
        continue;
      }
      String normalized = permission.toLowerCase(Locale.ROOT);
      if (!normalized.startsWith("webshop.market.limit.")) {
        continue;
      }
      String suffix = normalized.substring("webshop.market.limit.".length());
      try {
        int value = Integer.parseInt(suffix);
        if (value > maxLimit) {
          maxLimit = value;
        }
      } catch (NumberFormatException ignored) {
        continue;
      }
    }
    return maxLimit;
  }

  private int countActiveListings(Connection connection, long userId) throws SQLException {
    String sql = """
        SELECT COUNT(*) AS total
        FROM market_listings
        WHERE seller_user_id = ? AND status = 'ACTIVE'
        FOR UPDATE
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return 0;
        }
        return resultSet.getInt("total");
      }
    }
  }

  private String resolveSortColumn(String sort) {
    String normalized = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "price" -> "ml.price";
      case "quantity" -> "ml.quantity";
      case "created", "createdat", "time" -> "ml.id";
      default -> "ml.id";
    };
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

  private record ExistingTrade(
      long tradeId,
      long listingId,
      String currency,
      long totalPrice,
      long buyerTotal,
      long sellerReceive,
      long feeAmount,
      long taxAmount,
      String status,
      LocalDateTime refundDeadline) {
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
      String remark,
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

  record ListingQuery(
      Long sellerUserId,
      boolean activeOnly,
      String sort,
      boolean ascending,
      CurrencyType currency,
      Long minPrice,
      Long maxPrice,
      String material,
      String keyword,
      int limit) {
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
      String remark,
      String status,
      LocalDateTime createdAt) {
  }

  record AdminListingView(
      long id,
      long sellerUserId,
      String sellerName,
      UUID sellerUuid,
      Long buyerUserId,
      String buyerName,
      UUID buyerUuid,
      CurrencyType currency,
      long price,
      int quantity,
      String itemMaterial,
      String itemMetaJson,
      String remark,
      String status,
      LocalDateTime createdAt,
      LocalDateTime soldAt,
      LocalDateTime unlistedAt) {
  }

  record TradeResult(
      TradeState state,
      long tradeId,
      long listingId,
      CurrencyType currency,
      long totalPrice,
      long buyerTotal,
      long sellerReceive,
      long feeAmount,
      long taxAmount,
      String orderStatus,
      LocalDateTime refundDeadline,
      int cooldownSeconds) {
  }

  record UnlistResult(long listingId, CurrencyType currency, long price, int quantity) {
  }

  record ListingPriceUpdateResult(long listingId, CurrencyType currency, long price) {
  }

  record ListingRemarkUpdateResult(long listingId, String remark) {
  }
}
