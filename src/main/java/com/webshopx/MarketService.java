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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

class MarketService {
  private static final int DEFAULT_LIMIT = 100;

  private final JavaPlugin plugin;
  private final DatabaseManager databaseManager;
  private final WalletService walletService;
  private final Supplier<PluginSettings> settingsSupplier;
  private final ItemSnapshotCodec itemSnapshotCodec;

  MarketService(
      JavaPlugin plugin,
      DatabaseManager databaseManager,
      WalletService walletService,
      Supplier<PluginSettings> settingsSupplier) {
    this.plugin = plugin;
    this.databaseManager = databaseManager;
    this.walletService = walletService;
    this.settingsSupplier = settingsSupplier;
    this.itemSnapshotCodec = new ItemSnapshotCodec();
  }

  JavaPlugin plugin() {
    return plugin;
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
    ItemStack templateItem = listingItem.clone();
    templateItem.setAmount(1);
    ItemSnapshotCodec.Snapshot snapshot = itemSnapshotCodec.serialize(templateItem);
    BoundUser boundUser = databaseManager.withConnection(connection ->
        readBoundUserByUuid(connection, player.getUniqueId(), false));
    if (boundUser == null) {
      throw new ServiceException("not_bound", "Please set your web password in-game before listing items");
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
          listingLimit,
          SupplyConfig.manual()));
      return new ListingCreateResult(listingId, listingItem.getType().name(), amount, currency, price);
    } catch (Exception exception) {
      restoreItem(player, listingItem);
      throw exception;
    }
  }

  ListingCreateResult createListingFromStack(
      Player player,
      ItemStack listingItem,
      long price,
      CurrencyType currency) {
    if (price <= 0L) {
      throw new ServiceException("invalid_price", "Price must be positive");
    }
    if (listingItem == null || listingItem.getType() == Material.AIR || listingItem.getAmount() <= 0) {
      throw new ServiceException("invalid_item", "Listing item is empty");
    }
    ItemStack storedItem = listingItem.clone();
    ItemSnapshotCodec.Snapshot snapshot = itemSnapshotCodec.serialize(storedItem.clone());
    BoundUser boundUser = databaseManager.withConnection(connection ->
        readBoundUserByUuid(connection, player.getUniqueId(), false));
    if (boundUser == null) {
      throw new ServiceException("not_bound", "Please set your web password in-game before listing items");
    }
    int listingLimit = resolveListingLimit(player);
    long listingId = databaseManager.inTransaction(connection -> createListingInTransaction(
        connection,
        boundUser,
        currency,
        price,
        storedItem,
        snapshot,
        listingLimit,
        SupplyConfig.manual()));
    return new ListingCreateResult(listingId, storedItem.getType().name(), storedItem.getAmount(), currency, price);
  }

  ListingCreateResult createSupplyListingFromPlayer(
      Player player,
      long price,
      CurrencyType currency,
      int transferBatchSize,
      int transitMaxStock) {
    if (price <= 0L) {
      throw new ServiceException("invalid_price", "Price must be positive");
    }
    SupplyConfig normalizedSupply = normalizeSupplyConfig(transferBatchSize, transitMaxStock);
    ItemStack handItem = player.getInventory().getItemInMainHand();
    if (handItem.getType() == Material.AIR) {
      throw new ServiceException("empty_hand", "Main hand item is empty");
    }
    ItemStack templateItem = handItem.clone();
    templateItem.setAmount(1);

    Block targetBlock = player.getTargetBlockExact(6);
    SupplySource source = resolveSupplySource(targetBlock);
    BoundUser seller = databaseManager.withConnection(connection ->
        readBoundUserByUuid(connection, player.getUniqueId(), false));
    if (seller == null) {
      throw new ServiceException("not_bound", "Please set your web password in-game before listing items");
    }

    int listingLimit = resolveListingLimit(player);
    SupplyTransfer transfer = withdrawSupplyStock(
        new SupplySource(source.worldName(), source.x(), source.y(), source.z()),
        templateItem,
        normalizedSupply.transferBatchSize());
    if (transfer.loadedAmount() <= 0) {
      throw new ServiceException("supply_empty", "供货箱里没有匹配的货物可上架");
    }
    try {
      long listingId = databaseManager.inTransaction(connection -> createListingInTransaction(
          connection,
          seller,
          currency,
          price,
          transfer.loadedItem(),
          transfer.snapshot(),
          listingLimit,
          new SupplyConfig(
              SupplyMode.SUPPLY,
              normalizedSupply.transferBatchSize(),
              normalizedSupply.transitMaxStock(),
              new SupplySource(source.worldName(), source.x(), source.y(), source.z()),
              transfer.loadedAmount())));
      return new ListingCreateResult(
          listingId,
          transfer.loadedItem().getType().name(),
          transfer.loadedAmount(),
          currency,
          price);
    } catch (Exception exception) {
      restoreSupplyStock(
          new SupplySource(source.worldName(), source.x(), source.y(), source.z()),
          transfer.loadedItem(),
          transfer.loadedAmount());
      throw exception;
    }
  }

  ListingCreateResult createSupplyListingFromTemplate(
      Player player,
      SupplySourceDescriptor sourceDescriptor,
      ItemStack templateItem,
      long price,
      CurrencyType currency) {
    if (sourceDescriptor == null) {
      throw new ServiceException("supply_missing", "Supply container is unavailable");
    }
    if (templateItem == null || templateItem.getType() == Material.AIR) {
      throw new ServiceException("invalid_item", "模板物品不能为空");
    }
    SupplyConfig normalizedSupply = normalizeSupplyConfig(0, 0);
    ItemStack template = templateItem.clone();
    template.setAmount(1);
    BoundUser seller = databaseManager.withConnection(connection ->
        readBoundUserByUuid(connection, player.getUniqueId(), false));
    if (seller == null) {
      throw new ServiceException("not_bound", "Please set your web password in-game before listing items");
    }
    int listingLimit = resolveListingLimit(player);
    SupplySource source = new SupplySource(
        sourceDescriptor.worldName(),
        sourceDescriptor.x(),
        sourceDescriptor.y(),
        sourceDescriptor.z());
    SupplyTransfer transfer = withdrawSupplyStock(source, template, normalizedSupply.transferBatchSize());
    try {
      ItemStack storedItem = transfer.loadedAmount() > 0 ? transfer.loadedItem() : template;
      ItemSnapshotCodec.Snapshot snapshot = transfer.loadedAmount() > 0
          ? transfer.snapshot()
          : itemSnapshotCodec.serialize(template);
      long listingId = databaseManager.inTransaction(connection -> createListingInTransaction(
          connection,
          seller,
          currency,
          price,
          storedItem,
          snapshot,
          listingLimit,
          new SupplyConfig(
              SupplyMode.SUPPLY,
              normalizedSupply.transferBatchSize(),
              normalizedSupply.transitMaxStock(),
              source,
              transfer.loadedAmount())));
      return new ListingCreateResult(
          listingId,
          storedItem.getType().name(),
          transfer.loadedAmount(),
          currency,
          price);
    } catch (Exception exception) {
      if (transfer.loadedAmount() > 0) {
        restoreSupplyStock(source, transfer.loadedItem(), transfer.loadedAmount());
      }
      throw exception;
    }
  }

  List<ListingView> listListingsForPlayer(UUID playerUuid, int limit) {
    BoundUser seller = databaseManager.withConnection(connection ->
        readBoundUserByUuid(connection, playerUuid, false));
    if (seller == null) {
      throw new ServiceException("not_bound", "Please set your web password in-game before using the market");
    }
    return listListings(
        new ListingQuery(
            seller.userId(),
            false,
            "created",
            false,
            null,
            null,
            null,
            null,
            null,
        limit));
  }

  List<SellerTradeLog> listRecentSellerTradeLogs(UUID playerUuid, int limit) {
    BoundUser seller = databaseManager.withConnection(connection ->
        readBoundUserByUuid(connection, playerUuid, false));
    if (seller == null) {
      throw new ServiceException("not_bound", "Please set your web password in-game before using the market");
    }
    int normalizedLimit = Math.max(1, Math.min(limit, 20));
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT mt.id, mt.listing_id, mt.currency, mt.unit_price, mt.quantity, mt.total_price, mt.status,
                 mt.created_at, buyer.username AS buyer_name, ml.item_material
          FROM market_trades mt
          JOIN market_listings ml ON ml.id = mt.listing_id
          JOIN web_users buyer ON buyer.id = mt.buyer_user_id
          WHERE mt.seller_user_id = ?
          ORDER BY mt.id DESC
          LIMIT ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, seller.userId());
        statement.setInt(2, normalizedLimit);
        List<SellerTradeLog> logs = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            logs.add(new SellerTradeLog(
                resultSet.getLong("id"),
                resultSet.getLong("listing_id"),
                resultSet.getString("buyer_name"),
                resultSet.getString("item_material"),
                CurrencyType.valueOf(resultSet.getString("currency")),
                resultSet.getLong("unit_price"),
                resultSet.getInt("quantity"),
                resultSet.getLong("total_price"),
                resultSet.getString("status"),
                resultSet.getTimestamp("created_at").toLocalDateTime()));
          }
        }
        return logs;
      }
    });
  }

  SupplySourceDescriptor describeSupplySource(Block targetBlock) {
    SupplySource source = resolveSupplySource(targetBlock);
    return new SupplySourceDescriptor(source.worldName(), source.x(), source.y(), source.z());
  }

  List<ListingView> listListings(ListingQuery query) {
    int limit = normalizeLimit(query.limit());
    String sortColumn = resolveSortColumn(query.sort());
    String sortDirection = query.ascending() ? "ASC" : "DESC";

    return databaseManager.withConnection(connection -> {
      StringBuilder sql = new StringBuilder("""
          SELECT ml.id, ml.seller_user_id, u.username AS seller_name, ml.seller_uuid, ml.currency, ml.price,
                 ml.quantity, ml.quantity_total, ml.item_material, ml.item_meta_json,
                 ml.remark, ml.status, ml.created_at, ml.source_mode, ml.supply_batch_size,
                 ml.supply_max_stock, ml.supply_loaded_total, ml.supply_sold_total,
                 ml.supply_last_loaded_amount, ml.supply_last_loaded_at
          FROM market_listings ml
          JOIN web_users u ON u.id = ml.seller_user_id
          WHERE 1=1
          """);
      List<Object> params = new ArrayList<>();

      if (query.activeOnly()) {
        sql.append(" AND ((ml.status = 'ACTIVE' AND (ml.quantity > 0 OR ml.source_mode = 'SUPPLY'))"
            + " OR (ml.source_mode = 'SUPPLY' AND ml.status = 'PAUSED' AND ml.quantity = 0))");
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

  List<AdminListingView> listAllListings(
      String statusFilter,
      String sellerKeyword,
      String buyerKeyword,
      String materialFilter,
      String keyword,
      String currencyFilter,
      int requestedLimit) {
    int limit = normalizeLimit(requestedLimit);
    return databaseManager.withConnection(connection -> {
      List<String> clauses = new ArrayList<>();
      List<Object> params = new ArrayList<>();
      clauses.add("1=1");
      if (statusFilter != null && !statusFilter.isBlank()) {
        clauses.add("ml.status = ?");
        params.add(statusFilter.trim().toUpperCase(Locale.ROOT));
      }
      if (sellerKeyword != null && !sellerKeyword.isBlank()) {
        clauses.add("LOWER(us.username) LIKE ?");
        params.add("%" + sellerKeyword.trim().toLowerCase(Locale.ROOT) + "%");
      }
      if (buyerKeyword != null && !buyerKeyword.isBlank()) {
        clauses.add("LOWER(IFNULL(ub.username, '')) LIKE ?");
        params.add("%" + buyerKeyword.trim().toLowerCase(Locale.ROOT) + "%");
      }
      if (materialFilter != null && !materialFilter.isBlank()) {
        clauses.add("ml.item_material = ?");
        params.add(materialFilter.trim().toUpperCase(Locale.ROOT));
      }
      if (currencyFilter != null && !currencyFilter.isBlank()) {
        clauses.add("ml.currency = ?");
        params.add(currencyFilter.trim().toUpperCase(Locale.ROOT));
      }
      if (keyword != null && !keyword.isBlank()) {
        clauses.add(
            "(CAST(ml.id AS CHAR) LIKE ? OR LOWER(ml.item_material) LIKE ? OR LOWER(IFNULL(ml.remark, '')) LIKE ? "
                + "OR LOWER(us.username) LIKE ? OR LOWER(IFNULL(ub.username, '')) LIKE ?)");
        String fuzzy = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        params.add(fuzzy);
        params.add(fuzzy);
        params.add(fuzzy);
        params.add(fuzzy);
        params.add(fuzzy);
      }
      String sql = """
          SELECT ml.id, ml.seller_user_id, us.username AS seller_name, ml.seller_uuid,
                 ml.buyer_user_id, ub.username AS buyer_name, ml.buyer_uuid,
                 ml.currency, ml.price, ml.quantity, ml.quantity_total, ml.item_material, ml.item_meta_json,
                 ml.remark, ml.status, ml.created_at, ml.sold_at, ml.unlisted_at,
                 ml.source_mode, ml.supply_batch_size, ml.supply_max_stock, ml.supply_loaded_total,
                 ml.supply_sold_total, ml.supply_last_loaded_amount, ml.supply_last_loaded_at
          FROM market_listings ml
          JOIN web_users us ON us.id = ml.seller_user_id
          LEFT JOIN web_users ub ON ub.id = ml.buyer_user_id
          """
          + " WHERE " + String.join(" AND ", clauses)
          + " ORDER BY ml.id DESC LIMIT ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        for (int i = 0; i < params.size(); i++) {
          statement.setObject(i + 1, params.get(i));
        }
        statement.setInt(params.size() + 1, limit);
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

  TradeResult buyListing(
      long buyerUserId,
      long listingId,
      int buyQuantity,
      String idempotencyKey,
      String deliveryModeRaw) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    if (buyQuantity <= 0 || buyQuantity > 64) {
      throw new ServiceException("invalid_quantity", "Buy quantity must be between 1 and 64");
    }
    String normalizedIdempotency = normalizeIdempotencyKey(idempotencyKey);
    return databaseManager.inTransaction(connection ->
        buyListingInTransaction(
            connection,
            buyerUserId,
            listingId,
            buyQuantity,
            normalizedIdempotency,
            deliveryModeRaw));
  }

  UnlistResult unlist(long sellerUserId, long listingId) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    return databaseManager.inTransaction(connection ->
        unlistInTransaction(connection, sellerUserId, listingId));
  }

  ListingStatusResult pause(long sellerUserId, long listingId) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    return databaseManager.inTransaction(connection ->
        pauseInTransaction(connection, sellerUserId, listingId));
  }

  ListingStatusResult resume(long sellerUserId, long listingId) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    return databaseManager.inTransaction(connection ->
        resumeInTransaction(connection, sellerUserId, listingId));
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

  ListingSettingsUpdateResult updateListingSettings(
      long sellerUserId,
      long listingId,
      long price,
      CurrencyType currency,
      String remark,
      Integer supplyBatchSize,
      Integer supplyMaxStock) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    if (price <= 0L) {
      throw new ServiceException("invalid_price", "Price must be positive");
    }
    String normalizedRemark = normalizeRemark(remark);
    return databaseManager.inTransaction(connection ->
        updateListingSettingsInTransaction(
            connection,
            sellerUserId,
            listingId,
            price,
            currency,
            normalizedRemark,
            supplyBatchSize,
            supplyMaxStock));
  }

  SupplyRefreshResult refreshSupplyListing(long sellerUserId, long listingId) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    return databaseManager.inTransaction(connection ->
        refreshSupplyListingInTransaction(connection, sellerUserId, listingId, true));
  }

  SupplyRefreshResult refreshSupplyListing(long listingId) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    return databaseManager.inTransaction(connection ->
        refreshSupplyListingInTransaction(connection, null, listingId, false));
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
      int listingLimit,
      SupplyConfig supplyConfig) throws SQLException {
    int activeListings = countActiveListings(connection, seller.userId());
    if (activeListings >= listingLimit) {
      throw new ServiceException(
          "listing_limit",
          "当前上架数量已达上限 (" + listingLimit + ")");
    }

    int initialQuantity = supplyConfig.mode() == SupplyMode.SUPPLY
        ? Math.max(0, supplyConfig.initialLoadedAmount())
        : listingItem.getAmount();
    int quantityTotal = supplyConfig.mode() == SupplyMode.SUPPLY
        ? supplyConfig.transitMaxStock()
        : listingItem.getAmount();
    String initialStatus = supplyConfig.mode() == SupplyMode.SUPPLY && initialQuantity <= 0
        ? "PAUSED"
        : "ACTIVE";

    String sql = """
        INSERT INTO market_listings (
          seller_user_id, seller_uuid, currency, price, quantity, quantity_total, item_material, raw_item_blob,
          item_meta_json, remark, item_hash, source_mode,
          supply_world, supply_x, supply_y, supply_z, supply_batch_size, supply_max_stock,
          supply_loaded_total, supply_sold_total, supply_last_loaded_amount, supply_last_loaded_at, status
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement =
             connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, seller.userId());
      statement.setString(2, seller.boundUuid().toString());
      statement.setString(3, currency.name());
      statement.setLong(4, price);
      statement.setInt(5, initialQuantity);
      statement.setInt(6, quantityTotal);
      statement.setString(7, listingItem.getType().name());
      statement.setBytes(8, snapshot.rawItemBlob());
      statement.setString(9, snapshot.itemMetaJson());
      statement.setString(10, null);
      statement.setString(11, snapshot.itemHash());
      statement.setString(12, supplyConfig.mode().name());
      if (supplyConfig.source() == null) {
        statement.setString(13, null);
        statement.setObject(14, null);
        statement.setObject(15, null);
        statement.setObject(16, null);
      } else {
        statement.setString(13, supplyConfig.source().worldName());
        statement.setInt(14, supplyConfig.source().x());
        statement.setInt(15, supplyConfig.source().y());
        statement.setInt(16, supplyConfig.source().z());
      }
      if (supplyConfig.mode() == SupplyMode.SUPPLY) {
        statement.setInt(17, supplyConfig.transferBatchSize());
        statement.setInt(18, supplyConfig.transitMaxStock());
        statement.setLong(19, supplyConfig.initialLoadedAmount());
        statement.setLong(20, 0L);
        statement.setInt(21, supplyConfig.initialLoadedAmount());
        statement.setTimestamp(22, Timestamp.valueOf(LocalDateTime.now()));
      } else {
        statement.setObject(17, null);
        statement.setObject(18, null);
        statement.setLong(19, 0L);
        statement.setLong(20, 0L);
        statement.setObject(21, null);
        statement.setTimestamp(22, null);
      }
      statement.setString(23, initialStatus);
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
      int buyQuantity,
      String idempotencyKey,
      String deliveryModeRaw) throws SQLException {
    int cooldownSeconds = normalizedOrderCooldownSeconds();
    ExistingTrade existingTrade = readExistingTrade(connection, buyerUserId, idempotencyKey);
    if (existingTrade != null) {
      long buyerTotal = existingTrade.buyerTotal() > 0
          ? existingTrade.buyerTotal()
          : existingTrade.totalPrice();
      long sellerReceive = existingTrade.sellerReceive() > 0
          ? existingTrade.sellerReceive()
          : existingTrade.totalPrice();
      int effectiveCooldown = existingTrade.refundDeadline() == null ? 0 : cooldownSeconds;
      return new TradeResult(
          TradeState.EXISTING,
          existingTrade.tradeId(),
          existingTrade.listingId(),
          CurrencyType.valueOf(existingTrade.currency()),
          existingTrade.unitPrice(),
          existingTrade.quantity(),
          existingTrade.totalPrice(),
          buyerTotal,
          sellerReceive,
          existingTrade.feeAmount(),
          existingTrade.taxAmount(),
          existingTrade.status(),
          existingTrade.refundDeadline(),
          effectiveCooldown);
    }

    MarketListing listing = readListingForUpdate(connection, listingId);
    if (listing.isSupply() && listing.quantity() <= 0) {
      try {
        listing = replenishSupplyIfEmpty(connection, listing, false);
      } catch (ServiceException exception) {
        listing = readListingForUpdate(connection, listingId);
      }
    }
    if (!listing.status().equals("ACTIVE")) {
      throw new ServiceException("listing_unavailable", "Supply mode is not enabled for this listing");
    }
    if (listing.sellerUserId() == buyerUserId) {
      throw new ServiceException("invalid_trade", "You cannot buy your own listing");
    }
    if (buyQuantity > listing.quantity()) {
      throw new ServiceException("insufficient_quantity", "Listing does not have enough remaining quantity");
    }

    BoundUser buyer = readBoundUserById(connection, buyerUserId, true);
    PluginSettings.MarketEconomySettings marketEconomy = settingsSupplier.get().economySettings().marketSettings();
    long tradeSubtotal = Math.multiplyExact(listing.price(), buyQuantity);
    long fee = calculatePercent(tradeSubtotal, marketEconomy.tradeFeePercent());
    long tax = calculatePercent(tradeSubtotal, marketEconomy.tradeTaxPercent());
    long buyerTotal = Math.addExact(tradeSubtotal, tax);
    long sellerReceive = Math.max(0L, tradeSubtotal - fee);
    LocalDateTime now = LocalDateTime.now();
    DeliveryMode deliveryMode = resolveDeliveryMode(deliveryModeRaw);
    LocalDateTime refundDeadline = deliveryMode == DeliveryMode.IMMEDIATE && cooldownSeconds > 0
        ? now.plusSeconds(cooldownSeconds)
        : null;
    String tradeStatus = deliveryMode == DeliveryMode.CLAIM ? "WAIT_CLAIM" : "PENDING";

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
        buyQuantity,
        tradeSubtotal,
        buyerTotal,
        sellerReceive,
        fee,
        tax,
        idempotencyKey,
        tradeStatus,
        refundDeadline);
    listing = updateListingAfterPurchase(connection, listing, buyer, buyQuantity);
    if (listing.isSupply() && listing.quantity() <= 0) {
      try {
        listing = replenishSupplyIfEmpty(connection, listing, false);
      } catch (ServiceException exception) {
        listing = readListingForUpdate(connection, listing.id());
      }
    }
    LocalDateTime deliveryAt = refundDeadline == null ? now : refundDeadline;
    enqueueMarketItemDelivery(
        connection,
        listing.id(),
        tradeId,
        buyer.userId(),
        buyer.boundUuid(),
        listing.rawItemBlob(),
        buyQuantity,
        DeliveryType.SALE,
        deliveryMode == DeliveryMode.CLAIM ? "WAIT_CLAIM" : "PENDING",
        deliveryAt);
    return new TradeResult(
        TradeState.CREATED,
        tradeId,
        listing.id(),
        listing.currency(),
        listing.price(),
        buyQuantity,
        tradeSubtotal,
        buyerTotal,
        sellerReceive,
        fee,
        tax,
        tradeStatus,
        refundDeadline,
        deliveryMode == DeliveryMode.CLAIM ? 0 : cooldownSeconds);
  }

  private UnlistResult unlistInTransaction(Connection connection, long sellerUserId, long listingId)
      throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!"ACTIVE".equalsIgnoreCase(listing.status()) && !"PAUSED".equalsIgnoreCase(listing.status())) {
      throw new ServiceException("listing_unavailable", "Supply mode is not enabled for this listing");
    }
    if (listing.sellerUserId() != sellerUserId) {
      throw new ServiceException("forbidden", "Only the owner can unlist this listing");
    }
    BoundUser seller = readBoundUserById(connection, sellerUserId, true);

    String updateSql = """
        UPDATE market_listings
        SET status = 'UNLISTED', unlisted_at = NOW(), paused_at = NULL
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
      statement.setLong(1, listing.id());
      statement.executeUpdate();
    }

    enqueueMarketItemDelivery(
        connection,
        listing.id(),
        null,
        seller.userId(),
        seller.boundUuid(),
        listing.rawItemBlob(),
        listing.quantity(),
        DeliveryType.UNLIST,
        "PENDING",
        LocalDateTime.now());
    return new UnlistResult(listing.id(), listing.currency(), listing.price(), listing.quantity());
  }

  private ListingStatusResult pauseInTransaction(Connection connection, long sellerUserId, long listingId)
      throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!"ACTIVE".equalsIgnoreCase(listing.status())) {
      throw new ServiceException("listing_unavailable", "Supply mode is not enabled for this listing");
    }
    if (listing.sellerUserId() != sellerUserId) {
      throw new ServiceException("forbidden", "Only the owner can pause this listing");
    }
    String sql = """
        UPDATE market_listings
        SET status = 'PAUSED', paused_at = NOW()
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, listing.id());
      statement.executeUpdate();
    }
    return new ListingStatusResult(listing.id(), "PAUSED");
  }

  private ListingStatusResult resumeInTransaction(Connection connection, long sellerUserId, long listingId)
      throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!"PAUSED".equalsIgnoreCase(listing.status())) {
      throw new ServiceException("listing_unavailable", "Supply mode is not enabled for this listing");
    }
    if (listing.sellerUserId() != sellerUserId) {
      throw new ServiceException("forbidden", "Only the owner can resume this listing");
    }
    if (listing.quantity() <= 0 && listing.isSupply()) {
      listing = replenishSupplyIfEmpty(connection, listing, true);
    }
    if (listing.quantity() <= 0) {
      throw new ServiceException("listing_empty", "Listing has no remaining stock to resume");
    }
    String sql = """
        UPDATE market_listings
        SET status = 'ACTIVE', paused_at = NULL
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, listing.id());
      statement.executeUpdate();
    }
    return new ListingStatusResult(listing.id(), "ACTIVE");
  }

  private ListingPriceUpdateResult updateListingPriceInTransaction(
      Connection connection,
      long sellerUserId,
      long listingId,
      long newPrice) throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!"ACTIVE".equalsIgnoreCase(listing.status()) && !"PAUSED".equalsIgnoreCase(listing.status())) {
      throw new ServiceException("listing_unavailable", "Supply mode is not enabled for this listing");
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
    if (!"ACTIVE".equalsIgnoreCase(listing.status()) && !"PAUSED".equalsIgnoreCase(listing.status())) {
      throw new ServiceException("listing_unavailable", "Supply mode is not enabled for this listing");
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

  private ListingSettingsUpdateResult updateListingSettingsInTransaction(
      Connection connection,
      long sellerUserId,
      long listingId,
      long price,
      CurrencyType currency,
      String remark,
      Integer supplyBatchSize,
      Integer supplyMaxStock) throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!"ACTIVE".equalsIgnoreCase(listing.status()) && !"PAUSED".equalsIgnoreCase(listing.status())) {
      throw new ServiceException("listing_unavailable", "Supply mode is not enabled for this listing");
    }
    if (listing.sellerUserId() != sellerUserId) {
      throw new ServiceException("forbidden", "Only the owner can update listing settings");
    }
    Integer batch = listing.supplyBatchSize();
    Integer maxStock = listing.supplyMaxStock();
    int quantityTotal = listing.quantityTotal();
    if (listing.isSupply()) {
      SupplyConfig normalized = normalizeSupplyConfig(
          supplyBatchSize == null ? effectiveSupplyBatchSize(listing) : supplyBatchSize,
          supplyMaxStock == null ? effectiveSupplyMaxStock(listing) : supplyMaxStock);
      batch = normalized.transferBatchSize();
      maxStock = normalized.transitMaxStock();
      quantityTotal = Math.max(Math.max(0, listing.quantity()), maxStock);
    }

    String sql = """
        UPDATE market_listings
        SET price = ?, currency = ?, remark = ?, supply_batch_size = ?, supply_max_stock = ?, quantity_total = ?
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, price);
      statement.setString(2, currency.name());
      statement.setString(3, remark);
      if (batch == null) {
        statement.setObject(4, null);
      } else {
        statement.setInt(4, batch);
      }
      if (maxStock == null) {
        statement.setObject(5, null);
      } else {
        statement.setInt(5, maxStock);
      }
      statement.setInt(6, quantityTotal);
      statement.setLong(7, listingId);
      statement.executeUpdate();
    }
    MarketListing refreshed = readListingForUpdate(connection, listingId);
    return new ListingSettingsUpdateResult(
        refreshed.id(),
        refreshed.currency(),
        refreshed.price(),
        refreshed.remark(),
        refreshed.sourceMode(),
        refreshed.supplyBatchSize(),
        refreshed.supplyMaxStock(),
        refreshed.quantityTotal());
  }

  private SupplyRefreshResult refreshSupplyListingInTransaction(
      Connection connection,
      Long sellerUserId,
      long listingId,
      boolean manual) throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!listing.isSupply()) {
      throw new ServiceException("listing_unavailable", "Supply mode is not enabled for this listing");
    }
    if (sellerUserId != null && listing.sellerUserId() != sellerUserId) {
      throw new ServiceException("forbidden", "Only the owner can refresh this listing");
    }
    if (!"ACTIVE".equalsIgnoreCase(listing.status()) && !"PAUSED".equalsIgnoreCase(listing.status())) {
      throw new ServiceException("listing_unavailable", "Supply mode is not enabled for this listing");
    }
    return refreshSupplyListingLocked(connection, listing, manual).result();
  }

  private MarketListing replenishSupplyIfEmpty(
      Connection connection,
      MarketListing listing,
      boolean manual) throws SQLException {
    if (!listing.isSupply() || listing.quantity() > 0) {
      return listing;
    }
    return refreshSupplyListingLocked(connection, listing, manual).listing();
  }

  private SupplyRefreshState refreshSupplyListingLocked(
      Connection connection,
      MarketListing listing,
      boolean manual) throws SQLException {
    int currentStock = Math.max(0, listing.quantity());
    int maxStock = effectiveSupplyMaxStock(listing);
    int batchSize = effectiveSupplyBatchSize(listing);
    int space = Math.max(0, maxStock - currentStock);
    if (space <= 0) {
      return new SupplyRefreshState(
          new SupplyRefreshResult(
              listing.id(),
              0,
              currentStock,
              maxStock,
              listing.supplyLoadedTotal(),
              listing.supplySoldTotal(),
              listing.status()),
          listing);
    }
    SupplySource source = listing.supplySource();
    if (source == null) {
      pauseSupplyListing(connection, listing.id());
      notifySupplyPausedIfOnline(listing, "Supply container broken, listing #" + listing.id() + " has been paused.");
      throw new ServiceException("supply_missing", "Supply container is unavailable");
    }
    int requestAmount = Math.min(batchSize, space);
    ItemStack template = itemSnapshotCodec.deserialize(listing.rawItemBlob());
    template.setAmount(1);
    SupplyTransfer transfer;
    try {
      transfer = withdrawSupplyStock(source, template, requestAmount);
    } catch (ServiceException exception) {
      if ("supply_missing".equalsIgnoreCase(exception.code())) {
        pauseSupplyListing(connection, listing.id());
        notifySupplyPausedIfOnline(listing, "Supply container broken, listing #" + listing.id() + " has been paused.");
        throw new ServiceException("supply_missing", "Supply container is unavailable");
      }
      throw exception;
    }
    if (transfer.loadedAmount() <= 0) {
      String emptySql = """
          UPDATE market_listings
          SET supply_last_loaded_amount = 0
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(emptySql)) {
        statement.setLong(1, listing.id());
        statement.executeUpdate();
      }
      MarketListing refreshed = readListingForUpdate(connection, listing.id());
      return new SupplyRefreshState(
          new SupplyRefreshResult(
              listing.id(),
              0,
              refreshed.quantity(),
              effectiveSupplyMaxStock(refreshed),
              refreshed.supplyLoadedTotal(),
              refreshed.supplySoldTotal(),
              refreshed.status()),
          refreshed);
    }

    try {
      String sql = """
          UPDATE market_listings
          SET quantity = quantity + ?,
              quantity_total = ?,
              status = 'ACTIVE',
              supply_max_stock = ?,
              supply_last_loaded_amount = ?,
              supply_last_loaded_at = NOW(),
              supply_loaded_total = supply_loaded_total + ?
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setInt(1, transfer.loadedAmount());
        statement.setInt(2, maxStock);
        statement.setInt(3, maxStock);
        statement.setInt(4, transfer.loadedAmount());
        statement.setInt(5, transfer.loadedAmount());
        statement.setLong(6, listing.id());
        statement.executeUpdate();
      }
      MarketListing refreshed = readListingForUpdate(connection, listing.id());
      return new SupplyRefreshState(
          new SupplyRefreshResult(
              refreshed.id(),
              transfer.loadedAmount(),
              refreshed.quantity(),
              effectiveSupplyMaxStock(refreshed),
              refreshed.supplyLoadedTotal(),
              refreshed.supplySoldTotal(),
              refreshed.status()),
          refreshed);
    } catch (Exception exception) {
      restoreSupplyStock(source, transfer.loadedItem(), transfer.loadedAmount());
      throw exception;
    }
  }

  private int effectiveSupplyBatchSize(MarketListing listing) {
    PluginSettings.MarketSupplySettings settings = settingsSupplier.get().marketSupplySettings();
    int configured = listing.supplyBatchSize() == null ? settings.defaultTransferBatchSize() : listing.supplyBatchSize();
    int normalized = Math.max(1, configured);
    return Math.min(normalized, Math.max(1, settings.maxTransferBatchSize()));
  }

  private int effectiveSupplyMaxStock(MarketListing listing) {
    PluginSettings.MarketSupplySettings settings = settingsSupplier.get().marketSupplySettings();
    int configured = listing.supplyMaxStock() == null ? settings.defaultTransitStock() : listing.supplyMaxStock();
    int normalized = Math.max(1, configured);
    return Math.min(normalized, Math.max(1, settings.maxTransitStock()));
  }

  private SupplyConfig normalizeSupplyConfig(int transferBatchSize, int transitMaxStock) {
    PluginSettings.MarketSupplySettings settings = settingsSupplier.get().marketSupplySettings();
    int batch = transferBatchSize > 0 ? transferBatchSize : settings.defaultTransferBatchSize();
    int maxStock = transitMaxStock > 0 ? transitMaxStock : settings.defaultTransitStock();
    batch = Math.max(1, Math.min(batch, Math.max(1, settings.maxTransferBatchSize())));
    maxStock = Math.max(1, Math.min(maxStock, Math.max(1, settings.maxTransitStock())));
    if (batch > maxStock) {
      batch = maxStock;
    }
    return new SupplyConfig(SupplyMode.SUPPLY, batch, maxStock, null, 0);
  }

  private SupplySource resolveSupplySource(Block targetBlock) {
    if (targetBlock == null) {
      throw new ServiceException("supply_missing", "Supply container is unavailable");
    }
    BlockState state = targetBlock.getState();
    if (!(state instanceof Container)) {
      throw new ServiceException("supply_missing", "Supply container is unavailable");
    }
    return new SupplySource(
        targetBlock.getWorld().getName(),
        targetBlock.getX(),
        targetBlock.getY(),
        targetBlock.getZ());
  }

  private SupplyTransfer withdrawSupplyStock(
      SupplySource source,
      ItemStack template,
      int requestedAmount) {
    return runSync(() -> withdrawSupplyStockSync(source, template, requestedAmount));
  }

  private SupplyTransfer withdrawSupplyStockSync(
      SupplySource source,
      ItemStack template,
      int requestedAmount) {
    if (requestedAmount <= 0) {
      return new SupplyTransfer(0, template, itemSnapshotCodec.serialize(template));
    }
    Container container = resolveContainer(source);
    int remaining = requestedAmount;
    ItemStack loadedItem = template.clone();
    loadedItem.setAmount(1);
    for (int slot = 0; slot < container.getInventory().getSize(); slot++) {
      ItemStack stack = container.getInventory().getItem(slot);
      if (!isSameSupplyItem(stack, template)) {
        continue;
      }
      int taken = Math.min(remaining, stack.getAmount());
      ItemStack updated = stack.clone();
      updated.setAmount(stack.getAmount() - taken);
      container.getInventory().setItem(slot, updated.getAmount() <= 0 ? null : updated);
      remaining -= taken;
      if (remaining <= 0) {
        break;
      }
    }
    int loadedAmount = requestedAmount - remaining;
    if (loadedAmount <= 0) {
      return new SupplyTransfer(0, loadedItem, itemSnapshotCodec.serialize(loadedItem));
    }
    return new SupplyTransfer(loadedAmount, loadedItem, itemSnapshotCodec.serialize(loadedItem));
  }

  private void restoreSupplyStock(SupplySource source, ItemStack template, int amount) {
    if (amount <= 0) {
      return;
    }
    runSync(() -> {
      Container container = resolveContainer(source);
      int remaining = amount;
      while (remaining > 0) {
        ItemStack chunk = template.clone();
        chunk.setAmount(Math.min(chunk.getMaxStackSize(), remaining));
        remaining -= chunk.getAmount();
        container.getInventory().addItem(chunk);
      }
      return null;
    });
  }

  private Container resolveContainer(SupplySource source) {
    World world = Bukkit.getWorld(source.worldName());
    if (world == null) {
      throw new ServiceException("supply_missing", "Supply container is unavailable");
    }
    BlockState state = world.getBlockAt(source.x(), source.y(), source.z()).getState();
    if (!(state instanceof Container container)) {
      throw new ServiceException("supply_missing", "Supply container is unavailable");
    }
    return container;
  }

  private void pauseSupplyListing(Connection connection, long listingId) throws SQLException {
    String sql = """
        UPDATE market_listings
        SET status = 'PAUSED'
        WHERE id = ?
          AND status IN ('ACTIVE', 'PAUSED')
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, listingId);
      statement.executeUpdate();
    }
  }

  private void notifySupplyPausedIfOnline(MarketListing listing, String message) {
    Player player = Bukkit.getPlayer(listing.sellerUuid());
    if (player != null && player.isOnline()) {
      player.sendMessage(message);
    }
  }

  boolean isProtectedSupplyBlock(Block block) {
    if (block == null) {
      return false;
    }
    return findProtectedSupplyInfo(block) != null;
  }

  ProtectedSupplyInfo findProtectedSupplyInfo(Block block) {
    if (block == null) {
      return null;
    }
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT ml.id, ml.item_material, ml.status, ml.supply_world, ml.supply_x, ml.supply_y, ml.supply_z,
                 u.username AS seller_name
          FROM market_listings ml
          JOIN web_users u ON u.id = ml.seller_user_id
          WHERE ml.source_mode = 'SUPPLY'
            AND ml.status IN ('ACTIVE', 'PAUSED')
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql);
           ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String world = resultSet.getString("supply_world");
          Integer x = (Integer) resultSet.getObject("supply_x");
          Integer y = (Integer) resultSet.getObject("supply_y");
          Integer z = (Integer) resultSet.getObject("supply_z");
          if (world == null || x == null || y == null || z == null) {
            continue;
          }
          if (matchesProtectedBlock(block, new SupplySource(world, x, y, z))) {
            return new ProtectedSupplyInfo(
                resultSet.getLong("id"),
                resultSet.getString("seller_name"),
                resultSet.getString("item_material"),
                resultSet.getString("status"));
          }
        }
        return null;
      }
    });
  }

  private boolean matchesProtectedBlock(Block block, SupplySource source) {
    if (!block.getWorld().getName().equals(source.worldName())) {
      return false;
    }
    if (block.getX() == source.x() && block.getY() == source.y() && block.getZ() == source.z()) {
      return true;
    }
    Block sourceBlock = block.getWorld().getBlockAt(source.x(), source.y(), source.z());
    Material sourceType = sourceBlock.getType();
    if (sourceType != Material.CHEST && sourceType != Material.TRAPPED_CHEST) {
      return false;
    }
    if (block.getType() != sourceType || block.getY() != source.y()) {
      return false;
    }
    int dx = Math.abs(block.getX() - source.x());
    int dz = Math.abs(block.getZ() - source.z());
    return (dx == 1 && dz == 0) || (dx == 0 && dz == 1);
  }

  private boolean isSameSupplyItem(ItemStack stack, ItemStack template) {
    if (stack == null || stack.getType() == Material.AIR) {
      return false;
    }
    if (stack.getType() != template.getType()) {
      return false;
    }
    ItemStack stackUnit = stack.clone();
    stackUnit.setAmount(1);
    ItemStack templateUnit = template.clone();
    templateUnit.setAmount(1);
    return itemSnapshotCodec.serialize(stackUnit).itemHash()
        .equals(itemSnapshotCodec.serialize(templateUnit).itemHash());
  }

  private <T> T runSync(java.util.concurrent.Callable<T> task) {
    if (Bukkit.isPrimaryThread()) {
      try {
        return task.call();
      } catch (ServiceException exception) {
        throw exception;
      } catch (Exception exception) {
        throw new IllegalStateException("Supply operation failed", exception);
      }
    }
    try {
      return Bukkit.getScheduler().callSyncMethod(plugin, task).get(10, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ServiceException("sync_interrupted", "Supply operation interrupted; please try again later");
    } catch (TimeoutException exception) {
      throw new ServiceException("sync_timeout", "供货操作超时，请稍后重试");
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof ServiceException serviceException) {
        throw serviceException;
      }
      throw new IllegalStateException("Supply operation failed", cause);
    }
  }

  private ExistingTrade readExistingTrade(Connection connection, long buyerUserId, String idempotencyKey)
      throws SQLException {
    String sql = """
        SELECT t.id, t.listing_id, t.currency, t.unit_price, t.quantity, t.total_price,
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
            resultSet.getLong("unit_price"),
            resultSet.getInt("quantity"),
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
      long unitPrice,
      int quantity,
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
          listing_id, buyer_user_id, seller_user_id, currency, unit_price, quantity, total_price,
          buyer_total, seller_receive, fee_amount, tax_amount, idempotency_key,
          status, refund_deadline
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement =
             connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, listingId);
      statement.setLong(2, buyerUserId);
      statement.setLong(3, sellerUserId);
      statement.setString(4, currency.name());
      statement.setLong(5, unitPrice);
      statement.setInt(6, quantity);
      statement.setLong(7, totalPrice);
      statement.setLong(8, buyerTotal);
      statement.setLong(9, sellerReceive);
      statement.setLong(10, feeAmount);
      statement.setLong(11, taxAmount);
      statement.setString(12, idempotencyKey);
      statement.setString(13, status);
      if (refundDeadline == null) {
        statement.setTimestamp(14, null);
      } else {
        statement.setTimestamp(14, Timestamp.valueOf(refundDeadline));
      }
      statement.executeUpdate();
      try (ResultSet keyResult = statement.getGeneratedKeys()) {
        if (!keyResult.next()) {
          throw new IllegalStateException("Could not read generated market trade id");
        }
        long tradeId = keyResult.getLong(1);
        if ("WAIT_CLAIM".equalsIgnoreCase(status)) {
          ClaimTokenRepository.ensureMarketTradeToken(connection, tradeId);
        }
        return tradeId;
      }
    }
  }

  private MarketListing updateListingAfterPurchase(
      Connection connection,
      MarketListing listing,
      BoundUser buyer,
      int buyQuantity) throws SQLException {
    int remain = listing.quantity() - buyQuantity;
    if (remain < 0) {
      throw new ServiceException("insufficient_quantity", "Listing does not have enough remaining quantity");
    }
    boolean soldOut = remain == 0;
    String sql;
    if (listing.isSupply()) {
      sql = """
          UPDATE market_listings
          SET quantity = ?, status = 'ACTIVE',
              buyer_user_id = NULL, buyer_uuid = NULL, sold_at = CASE WHEN ? THEN NOW() ELSE sold_at END,
              supply_sold_total = supply_sold_total + ?
          WHERE id = ?
          """;
    } else if (soldOut) {
      sql = """
          UPDATE market_listings
          SET quantity = 0, status = 'SOLD', buyer_user_id = ?, buyer_uuid = ?, sold_at = NOW()
          WHERE id = ?
          """;
    } else {
      sql = """
          UPDATE market_listings
          SET quantity = ?, status = 'ACTIVE',
              buyer_user_id = NULL, buyer_uuid = NULL, sold_at = NULL
          WHERE id = ?
          """;
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      if (listing.isSupply()) {
        statement.setInt(1, remain);
        statement.setBoolean(2, soldOut);
        statement.setInt(3, buyQuantity);
        statement.setLong(4, listing.id());
      } else if (soldOut) {
        statement.setLong(1, buyer.userId());
        statement.setString(2, buyer.boundUuid().toString());
        statement.setLong(3, listing.id());
      } else {
        statement.setInt(1, remain);
        statement.setLong(2, listing.id());
      }
      statement.executeUpdate();
    }
    return readListingForUpdate(connection, listing.id());
  }

  private UnlistResult adminUnlistInTransaction(Connection connection, long listingId)
      throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!"ACTIVE".equalsIgnoreCase(listing.status()) && !"PAUSED".equalsIgnoreCase(listing.status())) {
      throw new ServiceException("listing_unavailable", "Supply mode is not enabled for this listing");
    }

    String updateSql = """
        UPDATE market_listings
        SET status = 'UNLISTED', unlisted_at = NOW(), paused_at = NULL
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
      statement.setLong(1, listing.id());
      statement.executeUpdate();
    }

    enqueueMarketItemDelivery(
        connection,
        listing.id(),
        null,
        listing.sellerUserId(),
        listing.sellerUuid(),
        listing.rawItemBlob(),
        listing.quantity(),
        DeliveryType.UNLIST,
        "PENDING",
        LocalDateTime.now());
    return new UnlistResult(listing.id(), listing.currency(), listing.price(), listing.quantity());
  }

  private void enqueueMarketItemDelivery(
      Connection connection,
      long listingId,
      Long tradeId,
      long targetUserId,
      UUID targetUuid,
      byte[] itemBlob,
      int quantity,
      DeliveryType deliveryType,
      String status,
      LocalDateTime nextRetryAt) throws SQLException {
    String sql = """
        INSERT INTO market_item_deliveries (
          listing_id, trade_id, target_user_id, target_uuid, item_blob, quantity, delivery_type, status, next_retry_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, listingId);
      if (tradeId == null) {
        statement.setObject(2, null);
      } else {
        statement.setLong(2, tradeId);
      }
      statement.setLong(3, targetUserId);
      statement.setString(4, targetUuid.toString());
      statement.setBytes(5, itemBlob);
      statement.setInt(6, quantity);
      statement.setString(7, deliveryType.name());
      statement.setString(8, status);
      statement.setTimestamp(9, Timestamp.valueOf(nextRetryAt));
      statement.executeUpdate();
    }
  }

  private DeliveryMode resolveDeliveryMode(String rawMode) {
    if (rawMode == null || rawMode.isBlank()) {
      return DeliveryMode.IMMEDIATE;
    }
    String normalized = rawMode.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "IMMEDIATE" -> DeliveryMode.IMMEDIATE;
      case "CLAIM", "MANUAL", "MANUAL_CLAIM" -> DeliveryMode.CLAIM;
      default -> throw new ServiceException("invalid_delivery_mode", "Delivery mode is invalid");
    };
  }

  private MarketListing readListingForUpdate(Connection connection, long listingId) throws SQLException {
    String sql = """
        SELECT id, seller_user_id, seller_uuid, currency, price, quantity, quantity_total,
               item_material, raw_item_blob,
               item_meta_json, remark, item_hash, status, source_mode,
               supply_world, supply_x, supply_y, supply_z, supply_batch_size, supply_max_stock,
               supply_loaded_total, supply_sold_total, supply_last_loaded_amount, supply_last_loaded_at
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
            resultSet.getInt("quantity_total"),
            resultSet.getString("item_material"),
            resultSet.getBytes("raw_item_blob"),
            resultSet.getString("item_meta_json"),
            resultSet.getString("remark"),
            resultSet.getString("item_hash"),
            resultSet.getString("status"),
            SupplyMode.fromRaw(resultSet.getString("source_mode")),
            resultSet.getString("supply_world"),
            (Integer) resultSet.getObject("supply_x"),
            (Integer) resultSet.getObject("supply_y"),
            (Integer) resultSet.getObject("supply_z"),
            (Integer) resultSet.getObject("supply_batch_size"),
            (Integer) resultSet.getObject("supply_max_stock"),
            resultSet.getLong("supply_loaded_total"),
            resultSet.getLong("supply_sold_total"),
            (Integer) resultSet.getObject("supply_last_loaded_amount"),
            resultSet.getTimestamp("supply_last_loaded_at") == null
                ? null
                : resultSet.getTimestamp("supply_last_loaded_at").toLocalDateTime());
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
          resultSet.getInt("quantity_total"),
          resultSet.getString("item_material"),
          resultSet.getString("item_meta_json"),
          resultSet.getString("remark"),
          resultSet.getString("status"),
          resultSet.getTimestamp("created_at").toLocalDateTime(),
          SupplyMode.fromRaw(resultSet.getString("source_mode")),
          (Integer) resultSet.getObject("supply_batch_size"),
          (Integer) resultSet.getObject("supply_max_stock"),
          resultSet.getLong("supply_loaded_total"),
          resultSet.getLong("supply_sold_total"),
          (Integer) resultSet.getObject("supply_last_loaded_amount"),
          resultSet.getTimestamp("supply_last_loaded_at") == null
              ? null
              : resultSet.getTimestamp("supply_last_loaded_at").toLocalDateTime()));
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
          resultSet.getInt("quantity_total"),
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
              : resultSet.getTimestamp("unlisted_at").toLocalDateTime(),
          SupplyMode.fromRaw(resultSet.getString("source_mode")),
          (Integer) resultSet.getObject("supply_batch_size"),
          (Integer) resultSet.getObject("supply_max_stock"),
          resultSet.getLong("supply_loaded_total"),
          resultSet.getLong("supply_sold_total"),
          (Integer) resultSet.getObject("supply_last_loaded_amount"),
          resultSet.getTimestamp("supply_last_loaded_at") == null
              ? null
              : resultSet.getTimestamp("supply_last_loaded_at").toLocalDateTime()));
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
      long unitPrice,
      int quantity,
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
      int quantityTotal,
      String itemMaterial,
      byte[] rawItemBlob,
      String itemMetaJson,
      String remark,
      String itemHash,
      String status,
      SupplyMode sourceMode,
      String supplyWorld,
      Integer supplyX,
      Integer supplyY,
      Integer supplyZ,
      Integer supplyBatchSize,
      Integer supplyMaxStock,
      long supplyLoadedTotal,
      long supplySoldTotal,
      Integer supplyLastLoadedAmount,
      LocalDateTime supplyLastLoadedAt) {

    boolean isSupply() {
      return sourceMode == SupplyMode.SUPPLY;
    }

    SupplySource supplySource() {
      if (!isSupply() || supplyWorld == null || supplyX == null || supplyY == null || supplyZ == null) {
        return null;
      }
      return new SupplySource(supplyWorld, supplyX, supplyY, supplyZ);
    }
  }

  enum SupplyMode {
    MANUAL,
    SUPPLY;

    static SupplyMode fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return MANUAL;
      }
      try {
        return SupplyMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException exception) {
        return MANUAL;
      }
    }
  }

  enum TradeState {
    CREATED,
    EXISTING
  }

  enum DeliveryType {
    SALE,
    UNLIST
  }

  enum DeliveryMode {
    IMMEDIATE,
    CLAIM
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
      int quantityTotal,
      String itemMaterial,
      String itemMetaJson,
      String remark,
      String status,
      LocalDateTime createdAt,
      SupplyMode sourceMode,
      Integer supplyBatchSize,
      Integer supplyMaxStock,
      long supplyLoadedTotal,
      long supplySoldTotal,
      Integer supplyLastLoadedAmount,
      LocalDateTime supplyLastLoadedAt) {
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
      int quantityTotal,
      String itemMaterial,
      String itemMetaJson,
      String remark,
      String status,
      LocalDateTime createdAt,
      LocalDateTime soldAt,
      LocalDateTime unlistedAt,
      SupplyMode sourceMode,
      Integer supplyBatchSize,
      Integer supplyMaxStock,
      long supplyLoadedTotal,
      long supplySoldTotal,
      Integer supplyLastLoadedAmount,
      LocalDateTime supplyLastLoadedAt) {
  }

  record TradeResult(
      TradeState state,
      long tradeId,
      long listingId,
      CurrencyType currency,
      long unitPrice,
      int quantity,
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

  record ListingStatusResult(long listingId, String status) {
  }

  record SellerTradeLog(
      long tradeId,
      long listingId,
      String buyerName,
      String itemMaterial,
      CurrencyType currency,
      long unitPrice,
      int quantity,
      long totalPrice,
      String status,
      LocalDateTime createdAt) {
  }

  record ListingSettingsUpdateResult(
      long listingId,
      CurrencyType currency,
      long price,
      String remark,
      SupplyMode sourceMode,
      Integer supplyBatchSize,
      Integer supplyMaxStock,
      int quantityTotal) {
  }

  record SupplyRefreshResult(
      long listingId,
      int loadedAmount,
      int currentStock,
      int maxStock,
      long loadedTotal,
      long soldTotal,
      String status) {
  }

  record ProtectedSupplyInfo(
      long listingId,
      String ownerName,
      String itemMaterial,
      String status) {
  }

  record SupplySourceDescriptor(String worldName, int x, int y, int z) {
  }

  private record SupplyRefreshState(SupplyRefreshResult result, MarketListing listing) {
  }

  private record SupplySource(String worldName, int x, int y, int z) {
  }

  private record SupplyTransfer(
      int loadedAmount,
      ItemStack loadedItem,
      ItemSnapshotCodec.Snapshot snapshot) {
  }

  private record SupplyConfig(
      SupplyMode mode,
      int transferBatchSize,
      int transitMaxStock,
      SupplySource source,
      int initialLoadedAmount) {

    static SupplyConfig manual() {
      return new SupplyConfig(SupplyMode.MANUAL, 0, 0, null, 0);
    }
  }
}



