package com.webshopx;

import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.bukkit.plugin.java.JavaPlugin;

class MarketService {
  private static final int DEFAULT_LIMIT = 100;
  private static final int AUCTION_SETTLE_BATCH_LIMIT = 20;
  private static final int DYNAMIC_DECAY_STEP = 1;
  private static final int MIN_AUCTION_DURATION_SECONDS = 30;
  private static final long DEFAULT_ANTI_SNIPING_WINDOW_SECONDS = 30L;
  private static final long DEFAULT_ANTI_SNIPING_EXTEND_SECONDS = 30L;
  private static final String ANTI_SNIPING_WINDOW_KEY = "antiSnipingWindowSeconds";
  private static final String ANTI_SNIPING_EXTEND_KEY = "antiSnipingExtendSeconds";
  private static final String TEMPLATE_MARKET_LISTED = "market_listed";
  private static final String TEMPLATE_MARKET_TRADE = "market_trade";
  private static final String TEMPLATE_AUCTION_BID_SELF = "auction_bid_self";
  private static final String TEMPLATE_AUCTION_BID_SELLER = "auction_bid_seller";
  private static final String TEMPLATE_AUCTION_OUTBID = "auction_outbid";
  private static final String TEMPLATE_AUCTION_SETTLEMENT = "auction_settlement";
  private static final String TEMPLATE_MARKET_BUY_ESCROW_REFUND = "market_buy_escrow_refund";

  private final JavaPlugin plugin;
  private final DatabaseManager databaseManager;
  private final WalletService walletService;
  private final RuntimeConfigService runtimeConfigService;
  private final Supplier<PluginSettings> settingsSupplier;
  private final MessageService messageService;
  private final NotificationService notificationService;
  private final BroadcastService broadcastService;
  private final PlayerPresenceService playerPresenceService;
  private final UserMarketSettingsService userMarketSettingsService;
  private final ItemSnapshotCodec itemSnapshotCodec;
  private final MarketTagService marketTagService;
  private final MarketLimitationService marketLimitationService;

  MarketService(
      JavaPlugin plugin,
      DatabaseManager databaseManager,
      WalletService walletService,
      RuntimeConfigService runtimeConfigService,
      Supplier<PluginSettings> settingsSupplier,
      MessageService messageService,
      NotificationService notificationService,
      BroadcastService broadcastService,
      PlayerPresenceService playerPresenceService,
      UserMarketSettingsService userMarketSettingsService) {
    this.plugin = plugin;
    this.databaseManager = databaseManager;
    this.walletService = walletService;
    this.runtimeConfigService = runtimeConfigService;
    this.settingsSupplier = settingsSupplier;
    this.messageService = messageService;
    this.notificationService = notificationService;
    this.broadcastService = broadcastService;
    this.playerPresenceService = playerPresenceService;
    this.userMarketSettingsService = userMarketSettingsService;
    this.itemSnapshotCodec = new ItemSnapshotCodec();
    this.marketTagService = new MarketTagService(runtimeConfigService, itemSnapshotCodec);
    this.marketLimitationService = new MarketLimitationService(runtimeConfigService, itemSnapshotCodec);
  }

  void refreshRuntimePolicies() {
    marketTagService.invalidateCache();
    marketLimitationService.invalidateCache();
  }

  JavaPlugin plugin() {
    return plugin;
  }

  ListingCreateResult createBuyListing(
      long ownerUserId,
      String itemMaterialRaw,
      long price,
      int quantity,
      CurrencyType currency,
      String tagCode) {
    if (price <= 0L) {
      throw new ServiceException("invalid_price", "Price must be positive");
    }
    if (quantity <= 0 || quantity > 64) {
      throw new ServiceException("invalid_quantity", "Quantity must be between 1 and 64");
    }
    String itemMaterial = String.valueOf(itemMaterialRaw).trim().toUpperCase(Locale.ROOT);
    Material material = Material.matchMaterial(itemMaterial);
    if (material == null || material == Material.AIR) {
      throw new ServiceException("invalid_item", "Item material is invalid");
    }
    ItemStack templateItem = new ItemStack(material, 1);
    ItemSnapshotCodec.Snapshot snapshot = itemSnapshotCodec.serialize(templateItem);

    ListingCreateResult result = databaseManager.inTransaction(connection -> {
      BoundUser owner = readBoundUserById(connection, ownerUserId, true);
      int listingLimit = resolveListingLimit(owner);
      int activeListings = countActiveListings(connection, owner.userId());
      if (activeListings >= listingLimit) {
        throw new ServiceException("listing_limit", "Active listing count reaches limit");
      }

      MarketLimitationService.Decision limitationDecision = evaluateLimitationDecision(
          owner.boundUuid(),
          MarketSide.BUY,
          TradeMode.DIRECT,
          currency,
          tagCode,
          templateItem.getType().name(),
          snapshot.rawItemBlob(),
          snapshot.itemMetaJson());
      applyLimitationDecision(
          limitationDecision,
          MarketSide.BUY,
          TradeMode.DIRECT,
          currency,
          tagCode);

      String resolvedTag = limitationDecision.forcedTag() == null
          ? tagCode
          : limitationDecision.forcedTag();
      MarketTagService.TagAssignment assignment = marketTagService.resolveTag(
          resolvedTag,
          snapshot.rawItemBlob(),
          snapshot.itemMetaJson(),
          templateItem.getType().name());
      marketTagService.syncDictionary(connection);

      long subtotal = Math.multiplyExact(price, quantity);
      long tax = calculatePercent(subtotal, settingsSupplier.get().economySettings().marketSettings().tradeTaxPercent());
      long escrow = Math.addExact(subtotal, tax);

      long listingId = createListingInTransaction(
          connection,
          owner,
          currency,
          price,
          templateItem,
          snapshot,
          listingLimit,
          SupplyConfig.manual(),
          MarketSide.BUY,
          assignment.code(),
          assignment.tagVersion(),
          escrow,
          escrow,
          quantity);
      applyCreateCostIfNeeded(
          connection,
          owner.userId(),
          currency,
          limitationDecision.createCost(),
          listingId);
      walletService.applyDelta(
          connection,
          owner.userId(),
          currency,
          -escrow,
          "MARKET_BUY_ESCROW",
          "mkt-buy-escrow:" + listingId,
          true);
      return new ListingCreateResult(
          listingId,
          templateItem.getType().name(),
          quantity,
          currency,
          price,
          MarketSide.BUY,
          assignment.code(),
          escrow,
          escrow);
    });
    String ownerName = databaseManager.withConnection(connection -> readBoundUserById(connection, ownerUserId, false))
        .username();
    publishListingCreatedEvent(ownerUserId, ownerName, result, TradeMode.DIRECT);
    plugin.getLogger().info(
        "MARKET_BUY_ORDER_CREATE ownerUserId="
            + ownerUserId
            + ", listingId="
            + result.listingId()
            + ", side=BUY, currency="
            + result.currency().name()
            + ", quantity="
            + result.quantity()
            + ", price="
            + result.price()
            + ", escrow="
            + result.escrowTotal());
    return result;
  }

  TradeResult fulfillBuyOrder(
      long sellerUserId,
      long listingId,
      int quantity,
      String idempotencyKey,
      String deliveryModeRaw) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    if (quantity <= 0 || quantity > 64) {
      throw new ServiceException("invalid_quantity", "Quantity must be between 1 and 64");
    }
    String normalizedIdempotency = normalizeIdempotencyKey(idempotencyKey);
    TradeResult result = databaseManager.inTransaction(connection -> fulfillBuyOrderInTransaction(
        connection,
        sellerUserId,
        listingId,
        quantity,
        normalizedIdempotency,
        deliveryModeRaw));
    if (result.state() == TradeState.CREATED) {
      publishTradeCreatedEvent(result.tradeId());
      plugin.getLogger().info(
          "MARKET_BUY_ORDER_FILL sellerUserId="
              + sellerUserId
              + ", listingId="
              + result.listingId()
              + ", side=BUY, currency="
              + result.currency().name()
              + ", quantity="
              + result.quantity()
              + ", price="
              + result.unitPrice());
    }
    return result;
  }

  List<MarketTagService.TagMeta> listMarketTagsMeta() {
    return databaseManager.withConnection(connection -> marketTagService.listTagMeta(connection));
  }

  TagRecalcResult recalcTags(String scopeRaw) {
    String normalizedScope = String.valueOf(scopeRaw == null ? "active" : scopeRaw)
        .trim()
        .toLowerCase(Locale.ROOT);
    if (!normalizedScope.equals("active") && !normalizedScope.equals("all")) {
      throw new ServiceException("bad_request", "Scope must be active or all");
    }
    TagRecalcResult result =
        databaseManager.inTransaction(connection -> recalcTagsInTransaction(connection, normalizedScope));
    plugin.getLogger().info(
        "MARKET_TAG_RECALC scope="
            + normalizedScope
            + ", scanned="
            + result.scanned()
            + ", changed="
            + result.changed()
            + ", elapsedMs="
            + result.elapsedMs());
    return result;
  }

  ListingCreateResult createListingFromPlayer(
      Player player,
      long price,
      int amount,
      CurrencyType currency) {
    return createListingFromPlayer(player, price, amount, currency, null);
  }

  ListingCreateResult createListingFromPlayer(
      Player player,
      long price,
      int amount,
      CurrencyType currency,
      String requestedTagCode) {
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

    int listingLimit = resolveListingLimit(boundUser);

    removeFromMainHand(player, amount);
    try {
      ListingCreateResult result = databaseManager.inTransaction(connection -> createSellListingInTransaction(
          connection,
          boundUser,
          currency,
          price,
          listingItem,
          snapshot,
          listingLimit,
          SupplyConfig.manual(),
          requestedTagCode));
      publishListingCreatedEvent(boundUser.userId(), player.getName(), result, TradeMode.DIRECT);
      return result;
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
    int listingLimit = resolveListingLimit(boundUser);
    ListingCreateResult result = databaseManager.inTransaction(connection -> createSellListingInTransaction(
        connection,
        boundUser,
        currency,
        price,
        storedItem,
        snapshot,
        listingLimit,
        SupplyConfig.manual(),
        null));
    publishListingCreatedEvent(boundUser.userId(), player.getName(), result, TradeMode.DIRECT);
    return result;
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

    int listingLimit = resolveListingLimit(seller);
    SupplyTransfer transfer = withdrawSupplyStock(
        new SupplySource(source.worldName(), source.x(), source.y(), source.z()),
        templateItem,
        normalizedSupply.transferBatchSize());
    if (transfer.loadedAmount() <= 0) {
      throw new ServiceException("supply_empty", "No matching stock was found in the selected supply container");
    }
    try {
      ListingCreateResult result = databaseManager.inTransaction(connection -> createSellListingInTransaction(
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
              transfer.loadedAmount()),
          null));
      publishListingCreatedEvent(seller.userId(), player.getName(), result, TradeMode.DIRECT);
      return result;
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
      throw new ServiceException("invalid_item", "濡剝婢橀悧鈺佹惂娑撳秷鍏樻稉铏光敄");
    }
    SupplyConfig normalizedSupply = normalizeSupplyConfig(0, 0);
    ItemStack template = templateItem.clone();
    template.setAmount(1);
    BoundUser seller = databaseManager.withConnection(connection ->
        readBoundUserByUuid(connection, player.getUniqueId(), false));
    if (seller == null) {
      throw new ServiceException("not_bound", "Please set your web password in-game before listing items");
    }
    int listingLimit = resolveListingLimit(seller);
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
      ListingCreateResult result = databaseManager.inTransaction(connection -> createSellListingInTransaction(
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
              transfer.loadedAmount()),
          null));
      publishListingCreatedEvent(seller.userId(), player.getName(), result, TradeMode.DIRECT);
      return result;
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
    return databaseManager.withConnection(
        connection -> listListings(connection, query, sortColumn, sortDirection, limit));
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
    return databaseManager.withConnection(connection -> listAllListings(
        connection,
        statusFilter,
        sellerKeyword,
        buyerKeyword,
        materialFilter,
        keyword,
        currencyFilter,
        limit));
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Dynamic clauses are assembled from validated enum values and constant SQL fragments")
  private List<ListingView> listListings(
      Connection connection,
      ListingQuery query,
      String sortColumn,
      String sortDirection,
      int limit) throws SQLException {
    StringBuilder sql = new StringBuilder("""
        SELECT ml.id, ml.seller_user_id, u.username AS seller_name, ml.seller_uuid, ml.currency, ml.price,
               ml.quantity, ml.quantity_total, ml.escrow_total, ml.escrow_remaining,
               ml.item_material, ml.display_name_override, ml.display_material,
               ml.display_icon_path, ml.item_meta_json,
               ml.remark, ml.status, ml.created_at,
               ml.market_side, ml.tag_code, ml.tag_version,
               ml.source_mode, ml.supply_batch_size,
               ml.supply_max_stock, ml.supply_loaded_total, ml.supply_sold_total,
          ml.supply_last_loaded_amount, ml.supply_last_loaded_at,
            ml.trade_mode, ml.dynamic_pricing_enabled, ml.dynamic_algorithm, ml.dynamic_params_json,
           ml.dynamic_base_price, ml.dynamic_floor_price, ml.dynamic_cap_price, ml.dynamic_price_step,
           ml.dynamic_demand_score,
           ml.auction_algorithm, ml.auction_params_json,
           ml.auction_start_price, ml.auction_min_increment, ml.auction_started_at,
           ml.auction_public_end_at, ml.auction_end_at,
         ml.auction_highest_bid, ml.auction_highest_bidder_user_id,
         ml.auction_highest_bidder_uuid, ml.auction_highest_bid_id, ml.auction_last_bid_at
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
    if (query.side() != null) {
      sql.append(" AND ml.market_side = ?");
      params.add(query.side().name());
    }
    if (query.tag() != null && !query.tag().isBlank()) {
      sql.append(" AND ml.tag_code = ?");
      params.add(query.tag());
    }
    if (query.tags() != null && !query.tags().isEmpty()) {
      List<String> tags = query.tags().stream()
          .filter(value -> value != null && !value.isBlank())
          .toList();
      if (!tags.isEmpty()) {
        sql.append(" AND ml.tag_code IN (");
        for (int i = 0; i < tags.size(); i++) {
          if (i > 0) {
            sql.append(", ");
          }
          sql.append("?");
        }
        sql.append(")");
        params.addAll(tags);
      }
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
      sql.append(" AND (LOWER(ml.item_material) LIKE ? "
          + "OR LOWER(COALESCE(ml.display_name_override, '')) LIKE ? "
          + "OR LOWER(COALESCE(ml.display_material, '')) LIKE ? "
          + "OR LOWER(u.username) LIKE ? OR LOWER(ml.remark) LIKE ?)");
      String keywordPattern = "%" + query.keyword().toLowerCase(Locale.ROOT) + "%";
      params.add(keywordPattern);
      params.add(keywordPattern);
      params.add(keywordPattern);
      params.add(keywordPattern);
      params.add(keywordPattern);
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
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Admin listing filters append constant SQL fragments and bind every user-provided value")
  private List<AdminListingView> listAllListings(
      Connection connection,
      String statusFilter,
      String sellerKeyword,
      String buyerKeyword,
      String materialFilter,
      String keyword,
      String currencyFilter,
      int limit) throws SQLException {
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
          "(CAST(ml.id AS CHAR) LIKE ? OR LOWER(ml.item_material) LIKE ? "
              + "OR LOWER(IFNULL(ml.display_name_override, '')) LIKE ? "
              + "OR LOWER(IFNULL(ml.display_material, '')) LIKE ? "
              + "OR LOWER(IFNULL(ml.remark, '')) LIKE ? "
              + "OR LOWER(us.username) LIKE ? OR LOWER(IFNULL(ub.username, '')) LIKE ?)");
      String fuzzy = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
      params.add(fuzzy);
      params.add(fuzzy);
      params.add(fuzzy);
      params.add(fuzzy);
      params.add(fuzzy);
      params.add(fuzzy);
      params.add(fuzzy);
    }
    String sql = """
        SELECT ml.id, ml.seller_user_id, us.username AS seller_name, ml.seller_uuid,
               ml.buyer_user_id, ub.username AS buyer_name, ml.buyer_uuid,
               ml.currency, ml.price, ml.quantity, ml.quantity_total,
               ml.market_side, ml.tag_code, ml.tag_version, ml.escrow_total, ml.escrow_remaining,
               ml.item_material,
               ml.display_name_override, ml.display_material, ml.display_icon_path, ml.item_meta_json,
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
    TradeResult result = databaseManager.inTransaction(connection ->
        buyListingInTransaction(
            connection,
            buyerUserId,
            listingId,
            buyQuantity,
            normalizedIdempotency,
            deliveryModeRaw));
    if (result.state() == TradeState.CREATED) {
      publishTradeCreatedEvent(result.tradeId());
    }
    return result;
  }

  BidResult placeBid(long bidderUserId, long listingId, long bidAmount, String idempotencyKey) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    if (bidAmount <= 0L) {
      throw new ServiceException("invalid_bid", "Bid amount must be positive");
    }
    String normalizedIdempotency = normalizeIdempotencyKey(idempotencyKey);
    BidResult result = databaseManager.inTransaction(
        connection -> placeBidInTransaction(connection, bidderUserId, listingId, bidAmount, normalizedIdempotency));
    if (result.state() == BidState.CREATED) {
      publishBidCreatedEvent(result);
    }
    return result;
  }

  void processMarketCycles() {
    try {
      List<AuctionSettlementNotice> notices =
          databaseManager.inTransaction(this::settleDueAuctions);
      notices.forEach(notice -> notifyPlayerAsync(notice.playerUuid(), notice.message()));
      persistAuctionNotices(notices);
      databaseManager.inTransaction(connection -> {
        applyDynamicPriceDecay(connection);
        return null;
      });
    } catch (Exception exception) {
      plugin.getLogger().warning("Market cycle processing failed: " + exception.getMessage());
    }
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
      String tagCode,
      String remark,
      String displayNameOverride,
      String displayMaterial,
      String displayIconPath,
      Integer supplyBatchSize,
      Integer supplyMaxStock,
      String tradeMode,
      Boolean dynamicPricingEnabled,
      String dynamicAlgorithm,
      String dynamicParamsJson,
      Long dynamicBasePrice,
      Long dynamicFloorPrice,
      Long dynamicCapPrice,
      Long dynamicPriceStep,
      String auctionAlgorithm,
      String auctionParamsJson,
      Long auctionStartPrice,
      Long auctionMinIncrement,
      LocalDateTime auctionEndAt) {
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
            tagCode,
            normalizedRemark,
            displayNameOverride,
            displayMaterial,
            displayIconPath,
            supplyBatchSize,
            supplyMaxStock,
            tradeMode,
            dynamicPricingEnabled,
            dynamicAlgorithm,
            dynamicParamsJson,
            dynamicBasePrice,
            dynamicFloorPrice,
            dynamicCapPrice,
            dynamicPriceStep,
            auctionAlgorithm,
            auctionParamsJson,
            auctionStartPrice,
            auctionMinIncrement,
            auctionEndAt));
  }

  ListingVisualUpdateResult updateListingDisplayIconPath(
      long sellerUserId,
      long listingId,
      String displayIconPath) {
    if (listingId <= 0L) {
      throw new ServiceException("invalid_listing", "Listing id must be positive");
    }
    String normalizedPath = normalizeDisplayIconPath(displayIconPath);
    return databaseManager.inTransaction(connection -> {
      MarketListing listing = readListingForUpdate(connection, listingId);
      if (!"ACTIVE".equalsIgnoreCase(listing.status()) && !"PAUSED".equalsIgnoreCase(listing.status())) {
        throw new ServiceException("listing_unavailable", "Listing is not editable");
      }
      if (listing.sellerUserId() != sellerUserId) {
        throw new ServiceException("forbidden", "Only the owner can update listing settings");
      }
      String sql = "UPDATE market_listings SET display_icon_path = ? WHERE id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, normalizedPath);
        statement.setLong(2, listingId);
        statement.executeUpdate();
      }
      MarketListing refreshed = readListingForUpdate(connection, listingId);
      return new ListingVisualUpdateResult(
          refreshed.id(),
          listing.displayIconPath(),
          refreshed.displayNameOverride(),
          refreshed.displayMaterial(),
          refreshed.displayIconPath());
    });
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

  private ListingCreateResult createSellListingInTransaction(
      Connection connection,
      BoundUser seller,
      CurrencyType currency,
      long price,
      ItemStack listingItem,
      ItemSnapshotCodec.Snapshot snapshot,
      int listingLimit,
      SupplyConfig supplyConfig,
      String requestedTagCode) throws SQLException {
    MarketLimitationService.Decision limitationDecision = evaluateLimitationDecision(
        seller.boundUuid(),
        MarketSide.SELL,
        TradeMode.DIRECT,
        currency,
        requestedTagCode,
        listingItem.getType().name(),
        snapshot.rawItemBlob(),
        snapshot.itemMetaJson());
    applyLimitationDecision(
        limitationDecision,
        MarketSide.SELL,
        TradeMode.DIRECT,
        currency,
        requestedTagCode);
    String requestedTag = limitationDecision.forcedTag() == null
        ? requestedTagCode
        : limitationDecision.forcedTag();
    MarketTagService.TagAssignment assignment = marketTagService.resolveTag(
        requestedTag,
        snapshot.rawItemBlob(),
        snapshot.itemMetaJson(),
        listingItem.getType().name());
    marketTagService.syncDictionary(connection);

    long listingId = createListingInTransaction(
        connection,
        seller,
        currency,
        price,
        listingItem,
        snapshot,
        listingLimit,
        supplyConfig,
        MarketSide.SELL,
        assignment.code(),
        assignment.tagVersion(),
        0L,
        0L,
        null);
    applyCreateCostIfNeeded(
        connection,
        seller.userId(),
        currency,
        limitationDecision.createCost(),
        listingId);

    int createdQuantity = supplyConfig.mode() == SupplyMode.SUPPLY
        ? Math.max(0, supplyConfig.initialLoadedAmount())
        : listingItem.getAmount();
    return new ListingCreateResult(
        listingId,
        listingItem.getType().name(),
        createdQuantity,
        currency,
        price,
        MarketSide.SELL,
        assignment.code(),
        0L,
        0L);
  }

  private long createListingInTransaction(
      Connection connection,
      BoundUser seller,
      CurrencyType currency,
      long price,
      ItemStack listingItem,
      ItemSnapshotCodec.Snapshot snapshot,
      int listingLimit,
      SupplyConfig supplyConfig,
      MarketSide marketSide,
      String tagCode,
      int tagVersion,
      long escrowTotal,
      long escrowRemaining,
      Integer quantityOverride) throws SQLException {
    int activeListings = countActiveListings(connection, seller.userId());
    if (activeListings >= listingLimit) {
      throw new ServiceException("listing_limit", "Active listing count reaches limit (" + listingLimit + ")");
    }
    if (marketSide == MarketSide.BUY && supplyConfig.mode() != SupplyMode.MANUAL) {
      throw new ServiceException("buy_requires_manual_source", "BUY listings must use MANUAL source mode");
    }

    int initialQuantity;
    int quantityTotal;
    if (quantityOverride != null) {
      initialQuantity = Math.max(0, quantityOverride);
      quantityTotal = Math.max(0, quantityOverride);
    } else {
      initialQuantity = supplyConfig.mode() == SupplyMode.SUPPLY
          ? Math.max(0, supplyConfig.initialLoadedAmount())
          : listingItem.getAmount();
      quantityTotal = supplyConfig.mode() == SupplyMode.SUPPLY
          ? supplyConfig.transitMaxStock()
          : listingItem.getAmount();
    }

    String initialStatus = marketSide == MarketSide.BUY
        ? "ACTIVE"
        : (supplyConfig.mode() == SupplyMode.SUPPLY && initialQuantity <= 0 ? "PAUSED" : "ACTIVE");
    String normalizedTag = normalizeTagCode(tagCode);
    if (normalizedTag == null) {
      normalizedTag = "default";
    }
    int normalizedTagVersion = Math.max(1, tagVersion);
    long normalizedEscrowTotal = marketSide == MarketSide.BUY ? Math.max(0L, escrowTotal) : 0L;
    long normalizedEscrowRemaining = marketSide == MarketSide.BUY ? Math.max(0L, escrowRemaining) : 0L;

    String sql = """
        INSERT INTO market_listings (
          seller_user_id, seller_uuid, currency, price, quantity, quantity_total, escrow_total, escrow_remaining,
          item_material, raw_item_blob,
          display_name_override, display_material, display_icon_path, item_meta_json, remark, item_hash,
          tag_code, tag_version, source_mode,
          supply_world, supply_x, supply_y, supply_z, supply_batch_size, supply_max_stock,
          supply_loaded_total, supply_sold_total, supply_last_loaded_amount, supply_last_loaded_at,
          status, market_side
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement =
             connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, seller.userId());
      statement.setString(2, seller.boundUuid().toString());
      statement.setString(3, currency.name());
      statement.setLong(4, price);
      statement.setInt(5, initialQuantity);
      statement.setInt(6, quantityTotal);
      statement.setLong(7, normalizedEscrowTotal);
      statement.setLong(8, normalizedEscrowRemaining);
      statement.setString(9, listingItem.getType().name());
      statement.setBytes(10, snapshot.rawItemBlob());
      statement.setString(11, null);
      statement.setString(12, null);
      statement.setString(13, null);
      statement.setString(14, snapshot.itemMetaJson());
      statement.setString(15, null);
      statement.setString(16, snapshot.itemHash());
      statement.setString(17, normalizedTag);
      statement.setInt(18, normalizedTagVersion);
      statement.setString(19, supplyConfig.mode().name());
      if (supplyConfig.source() == null) {
        statement.setString(20, null);
        statement.setObject(21, null);
        statement.setObject(22, null);
        statement.setObject(23, null);
      } else {
        statement.setString(20, supplyConfig.source().worldName());
        statement.setInt(21, supplyConfig.source().x());
        statement.setInt(22, supplyConfig.source().y());
        statement.setInt(23, supplyConfig.source().z());
      }
      if (supplyConfig.mode() == SupplyMode.SUPPLY) {
        statement.setInt(24, supplyConfig.transferBatchSize());
        statement.setInt(25, supplyConfig.transitMaxStock());
        statement.setLong(26, supplyConfig.initialLoadedAmount());
        statement.setLong(27, 0L);
        statement.setInt(28, supplyConfig.initialLoadedAmount());
        statement.setTimestamp(29, Timestamp.valueOf(LocalDateTime.now()));
      } else {
        statement.setObject(24, null);
        statement.setObject(25, null);
        statement.setLong(26, 0L);
        statement.setLong(27, 0L);
        statement.setObject(28, null);
        statement.setTimestamp(29, null);
      }
      statement.setString(30, initialStatus);
      statement.setString(31, marketSide.name());
      statement.executeUpdate();
      try (ResultSet keyResult = statement.getGeneratedKeys()) {
        if (!keyResult.next()) {
          throw new IllegalStateException("Could not read generated market listing id");
        }
        return keyResult.getLong(1);
      }
    }
  }

  private MarketLimitationService.Decision evaluateLimitationDecision(
      UUID actorUuid,
      MarketSide side,
      TradeMode tradeMode,
      CurrencyType currency,
      String tagCode,
      String itemMaterial,
      byte[] rawItemBlob,
      String itemMetaJson) {
    boolean bypass = hasLimitationBypass(actorUuid);
    MarketLimitationService.Decision decision = marketLimitationService.evaluate(
        new MarketLimitationService.DecisionContext(
            actorUuid,
            side.name(),
            tradeMode.name(),
            currency.name(),
            tagCode,
            itemMaterial,
            rawItemBlob,
            itemMetaJson),
        bypass);
    if (!decision.allowed()) {
      plugin.getLogger().info(
          "MARKET_LIMITATION_DENY actor="
              + actorUuid
              + ", side="
              + side.name()
              + ", tradeMode="
              + tradeMode.name()
              + ", currency="
              + currency.name()
              + ", code="
              + decision.denyCode()
              + ", ruleId="
              + decision.ruleId()
              + ", priority="
              + decision.rulePriority());
    }
    return decision;
  }

  private void applyLimitationDecision(
      MarketLimitationService.Decision decision,
      MarketSide side,
      TradeMode tradeMode,
      CurrencyType currency,
      String tagCode) {
    if (!decision.allowed()) {
      throw new ServiceException(
          decision.denyCode() == null ? "limitation_item_forbidden" : decision.denyCode(),
          "Listing is denied by market limitation");
    }
    if (!decision.allowedSides().isEmpty() && !decision.allowedSides().contains(side.name())) {
      throw new ServiceException("limitation_side_not_allowed", "Market side is not allowed");
    }
    if (!decision.allowedTradeModes().isEmpty() && !decision.allowedTradeModes().contains(tradeMode.name())) {
      throw new ServiceException("limitation_trade_mode_not_allowed", "Trade mode is not allowed");
    }
    if (!decision.allowedCurrencies().isEmpty() && !decision.allowedCurrencies().contains(currency.name())) {
      throw new ServiceException("limitation_currency_not_allowed", "Currency is not allowed");
    }
    String normalizedTag = normalizeTagCode(tagCode);
    if (normalizedTag != null && !decision.allowedTags().isEmpty()
        && !decision.allowedTags().contains(normalizedTag)) {
      throw new ServiceException("invalid_tag", "Tag is not allowed");
    }
  }

  private void applyCreateCostIfNeeded(
      Connection connection,
      long ownerUserId,
      CurrencyType listingCurrency,
      MarketLimitationService.CreateCost createCost,
      long listingId) throws SQLException {
    if (createCost == null || !createCost.enabled() || createCost.amount() <= 0L) {
      return;
    }
    CurrencyType costCurrency;
    if ("INHERIT".equalsIgnoreCase(createCost.currency())) {
      costCurrency = listingCurrency;
    } else {
      costCurrency = CurrencyType.fromConfig(createCost.currency());
    }
    walletService.applyDelta(
        connection,
        ownerUserId,
        costCurrency,
        -createCost.amount(),
        "MARKET_CREATE_COST",
        "mkt-create-cost:" + listingId,
        true);
  }

  private String normalizeTagCode(String raw) {
    if (raw == null) {
      return null;
    }
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return null;
    }
    normalized = normalized.replaceAll("[^a-z0-9_-]+", "_");
    normalized = normalized.replaceAll("^_+|_+$", "");
    return normalized.isEmpty() ? null : normalized;
  }

  private boolean hasLimitationBypass(UUID actorUuid) {
    if (actorUuid == null) {
      return false;
    }
    Player player = Bukkit.getPlayer(actorUuid);
    return player != null && player.hasPermission("webshop.market.limitation.bypass");
  }

  private TagRecalcResult recalcTagsInTransaction(Connection connection, String scope) throws SQLException {
    long started = System.currentTimeMillis();
    marketTagService.syncDictionary(connection);
    int targetVersion = marketTagService.currentTagVersion();

    String sql;
    if ("active".equals(scope)) {
      sql = """
          SELECT id, raw_item_blob, item_meta_json, item_material, tag_code, tag_version
          FROM market_listings
          WHERE status IN ('ACTIVE', 'PAUSED')
          FOR UPDATE
          """;
    } else {
      sql = """
          SELECT id, raw_item_blob, item_meta_json, item_material, tag_code, tag_version
          FROM market_listings
          FOR UPDATE
          """;
    }
    int scanned = 0;
    int changed = 0;
    String updateSql = """
        UPDATE market_listings
        SET tag_code = ?, tag_version = ?
        WHERE id = ?
        """;
    try (PreparedStatement query = connection.prepareStatement(sql);
         ResultSet resultSet = query.executeQuery();
         PreparedStatement update = connection.prepareStatement(updateSql)) {
      while (resultSet.next()) {
        scanned++;
        long listingId = resultSet.getLong("id");
        String currentTag = normalizeTagCode(resultSet.getString("tag_code"));
        int currentVersion = resultSet.getInt("tag_version");
        MarketTagService.TagAssignment assignment = marketTagService.resolveTag(
            null,
            resultSet.getBytes("raw_item_blob"),
            resultSet.getString("item_meta_json"),
            resultSet.getString("item_material"));
        if (!assignment.code().equals(currentTag) || currentVersion != targetVersion) {
          update.setString(1, assignment.code());
          update.setInt(2, targetVersion);
          update.setLong(3, listingId);
          update.addBatch();
          changed++;
        }
      }
      update.executeBatch();
    }
    return new TagRecalcResult(scanned, changed, Math.max(0L, System.currentTimeMillis() - started));
  }

  private long refundBuyEscrowIfNeeded(Connection connection, MarketListing listing, String reason)
      throws SQLException {
    if (listing.marketSide() != MarketSide.BUY) {
      return 0L;
    }
    long refundable = Math.max(0L, listing.escrowRemaining());
    if (refundable <= 0L) {
      return 0L;
    }
    walletService.applyDelta(
        connection,
        listing.sellerUserId(),
        listing.currency(),
        refundable,
        "MARKET_BUY_REFUND_ESCROW",
        "mkt-buy-refund:" + listing.id() + ":" + reason,
        false);
    String sql = "UPDATE market_listings SET escrow_remaining = 0 WHERE id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, listing.id());
      statement.executeUpdate();
    }
    plugin.getLogger().info(
        "MARKET_BUY_ORDER_REFUND_ESCROW listingId="
            + listing.id()
            + ", ownerUserId="
            + listing.sellerUserId()
            + ", side=BUY, currency="
            + listing.currency().name()
            + ", amount="
            + refundable
            + ", reason="
            + reason);
    enqueueBuyEscrowRefundNotification(listing, refundable);
    return refundable;
  }

  private void enqueueBuyEscrowRefundNotification(MarketListing listing, long refundable) {
    if (listing == null || refundable <= 0L) {
      return;
    }
    Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
      try {
        String amountText = formatAmount(refundable, listing.currency());
        notifyMarketEvent(
            listing.sellerUserId(),
            "MARKET_BUY_ORDER_REFUND_ESCROW",
            "收购托管退款",
            TEMPLATE_MARKET_BUY_ESCROW_REFUND,
            "收购单 #" + listing.id() + " 托管金额已退回：" + amountText + "。",
            Map.of(
                "listingId", listing.id(),
                "amountText", amountText));
      } catch (Exception ignored) {
        // Keep business path stable even when notification fails.
      }
    }, 1L);
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
    if (listing.marketSide() == MarketSide.BUY) {
      throw new ServiceException("buy_order_not_active", "BUY listings must be fulfilled via sell-to-buy");
    }
    if (listing.isAuction()) {
      MarketAlgorithmRegistry.AuctionAlgorithmType algorithmType = listing.auctionAlgorithmType();
      if (!MarketAlgorithmRegistry.supportsDirectBuy(algorithmType)) {
        throw new ServiceException("auction_only_bid", "This listing is in auction mode and can only be bid on");
      }
      return buyDutchAuctionInTransaction(
          connection,
          listing,
          buyerUserId,
          buyQuantity,
          idempotencyKey,
          deliveryModeRaw);
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

  private TradeResult fulfillBuyOrderInTransaction(
      Connection connection,
      long sellerUserId,
      long listingId,
      int fulfillQuantity,
      String idempotencyKey,
      String deliveryModeRaw) throws SQLException {
    int cooldownSeconds = normalizedOrderCooldownSeconds();
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (listing.marketSide() != MarketSide.BUY) {
      throw new ServiceException("buy_order_not_active", "Only BUY listings can be fulfilled");
    }
    String fulfillIdempotency = "fulfill:" + sellerUserId + ":" + idempotencyKey;
    ExistingTrade existingTrade = readExistingTrade(connection, listing.sellerUserId(), fulfillIdempotency);
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
    if (!"ACTIVE".equalsIgnoreCase(listing.status())) {
      throw new ServiceException("buy_order_not_active", "BUY listing is not active");
    }
    if (listing.sellerUserId() == sellerUserId) {
      throw new ServiceException("cannot_fulfill_own_buy_order", "You cannot fulfill your own BUY listing");
    }
    if (fulfillQuantity > listing.quantity()) {
      throw new ServiceException("insufficient_quantity", "Listing does not have enough remaining quantity");
    }
    BoundUser seller = readBoundUserById(connection, sellerUserId, true);
    BoundUser owner = readBoundUserById(connection, listing.sellerUserId(), true);

    PluginSettings.MarketEconomySettings marketEconomy = settingsSupplier.get().economySettings().marketSettings();
    long tradeSubtotal = Math.multiplyExact(listing.price(), fulfillQuantity);
    long fee = calculatePercent(tradeSubtotal, marketEconomy.tradeFeePercent());
    long tax = calculatePercent(tradeSubtotal, marketEconomy.tradeTaxPercent());
    long buyerTotal = Math.addExact(tradeSubtotal, tax);
    if (listing.escrowRemaining() < buyerTotal) {
      throw new ServiceException("buy_escrow_insufficient", "BUY escrow does not cover this fulfill amount");
    }
    long sellerReceive = Math.max(0L, tradeSubtotal - fee);
    LocalDateTime now = LocalDateTime.now();
    DeliveryMode deliveryMode = resolveDeliveryMode(deliveryModeRaw);
    LocalDateTime refundDeadline = deliveryMode == DeliveryMode.IMMEDIATE && cooldownSeconds > 0
        ? now.plusSeconds(cooldownSeconds)
        : null;
    String tradeStatus = deliveryMode == DeliveryMode.CLAIM ? "WAIT_CLAIM" : "PENDING";
    boolean consumedItems = false;
    try {
      consumeBuyFulfillItems(seller.boundUuid(), listing.rawItemBlob(), fulfillQuantity);
      consumedItems = true;

      long tradeId = insertTrade(
          connection,
          listing.id(),
          owner.userId(),
          seller.userId(),
          listing.currency(),
          listing.price(),
          fulfillQuantity,
          tradeSubtotal,
          buyerTotal,
          sellerReceive,
          fee,
          tax,
          fulfillIdempotency,
          tradeStatus,
          refundDeadline);

      int remainingQuantity = listing.quantity() - fulfillQuantity;
      long remainingEscrow = listing.escrowRemaining() - buyerTotal;
      String updateSql = """
          UPDATE market_listings
          SET quantity = ?,
              status = ?,
              sold_at = CASE WHEN ? THEN NOW() ELSE sold_at END,
              buyer_user_id = NULL,
              buyer_uuid = NULL,
              escrow_remaining = ?
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
        statement.setInt(1, remainingQuantity);
        statement.setString(2, remainingQuantity <= 0 ? "SOLD" : "ACTIVE");
        statement.setBoolean(3, remainingQuantity <= 0);
        statement.setLong(4, Math.max(0L, remainingEscrow));
        statement.setLong(5, listing.id());
        statement.executeUpdate();
      }
      if (remainingQuantity <= 0) {
        MarketListing refreshed = readListingForUpdate(connection, listing.id());
        refundBuyEscrowIfNeeded(connection, refreshed, "sold");
      }

      LocalDateTime deliveryAt = refundDeadline == null ? now : refundDeadline;
      enqueueMarketItemDelivery(
          connection,
          listing.id(),
          tradeId,
          owner.userId(),
          owner.boundUuid(),
          listing.rawItemBlob(),
          fulfillQuantity,
          DeliveryType.SALE,
          deliveryMode == DeliveryMode.CLAIM ? "WAIT_CLAIM" : "PENDING",
          deliveryAt);
      return new TradeResult(
          TradeState.CREATED,
          tradeId,
          listing.id(),
          listing.currency(),
          listing.price(),
          fulfillQuantity,
          tradeSubtotal,
          buyerTotal,
          sellerReceive,
          fee,
          tax,
          tradeStatus,
          refundDeadline,
          deliveryMode == DeliveryMode.CLAIM ? 0 : cooldownSeconds);
    } catch (Exception exception) {
      if (consumedItems) {
        restoreBuyFulfillItemsSafely(seller.boundUuid(), listing.rawItemBlob(), fulfillQuantity);
      }
      throw exception;
    }
  }

  private TradeResult buyDutchAuctionInTransaction(
      Connection connection,
      MarketListing listing,
      long buyerUserId,
      int buyQuantity,
      String idempotencyKey,
      String deliveryModeRaw) throws SQLException {
    if (!listing.isAuction() || !listing.isDutchAuction()) {
      throw new ServiceException("invalid_trade_mode", "Listing is not a Dutch auction");
    }
    if (listing.sellerUserId() == buyerUserId) {
      throw new ServiceException("invalid_trade", "You cannot buy your own listing");
    }
    if (buyQuantity != 1) {
      throw new ServiceException("invalid_quantity", "Dutch auction purchase quantity must be 1");
    }
    if (listing.quantity() <= 0) {
      throw new ServiceException("listing_unavailable", "Listing does not have remaining quantity");
    }

    LocalDateTime now = LocalDateTime.now();
    if (listing.auctionEndAt() == null || !listing.auctionEndAt().isAfter(now)) {
      throw new ServiceException("auction_closed", "This Dutch auction has ended");
    }

    if (hasPendingAuctionBids(connection, listing.id())) {
      refundAuctionBidsIfPresent(connection, listing, "dutch-direct-buy");
      listing = readListingForUpdate(connection, listing.id());
      now = LocalDateTime.now();
    }

    BoundUser buyer = readBoundUserById(connection, buyerUserId, true);
    JsonObject auctionParams = MarketAlgorithmRegistry.parseParams(listing.auctionParamsJson());
    long floorPrice = Math.max(1L, MarketAlgorithmRegistry.getLongParam(auctionParams, "floorPrice", 1L));
    long startPrice = listing.auctionStartPrice() == null ? Math.max(1L, listing.price()) : Math.max(1L, listing.auctionStartPrice());
    long finalBid = MarketAlgorithmRegistry.computeDutchPrice(
        startPrice,
        floorPrice,
        listing.auctionStartedAt(),
        listing.auctionEndAt(),
        now);

    PluginSettings.MarketEconomySettings marketEconomy = settingsSupplier.get().economySettings().marketSettings();
    long fee = calculatePercent(finalBid, marketEconomy.tradeFeePercent());
    long tax = calculatePercent(finalBid, marketEconomy.tradeTaxPercent());
    long buyerTotal = Math.addExact(finalBid, tax);
    long sellerReceive = Math.max(0L, finalBid - fee);
    int cooldownSeconds = normalizedOrderCooldownSeconds();
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
        finalBid,
        1,
        finalBid,
        buyerTotal,
        sellerReceive,
        fee,
        tax,
        idempotencyKey,
        tradeStatus,
        refundDeadline);

    String soldSql = """
        UPDATE market_listings
        SET quantity = 0,
            status = 'SOLD',
            buyer_user_id = ?,
            buyer_uuid = ?,
            sold_at = NOW(),
            price = ?,
            auction_highest_bid = ?,
            auction_highest_bidder_user_id = ?,
            auction_highest_bidder_uuid = ?,
            auction_highest_bid_id = NULL,
            auction_last_bid_at = NOW()
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(soldSql)) {
      statement.setLong(1, buyer.userId());
      statement.setString(2, buyer.boundUuid().toString());
      statement.setLong(3, finalBid);
      statement.setLong(4, finalBid);
      statement.setLong(5, buyer.userId());
      statement.setString(6, buyer.boundUuid().toString());
      statement.setLong(7, listing.id());
      statement.executeUpdate();
    }

    enqueueMarketItemDelivery(
        connection,
        listing.id(),
        tradeId,
        buyer.userId(),
        buyer.boundUuid(),
        listing.rawItemBlob(),
        listing.quantity(),
        DeliveryType.SALE,
        deliveryMode == DeliveryMode.CLAIM ? "WAIT_CLAIM" : "PENDING",
        refundDeadline == null ? now : refundDeadline);

    return new TradeResult(
        TradeState.CREATED,
        tradeId,
        listing.id(),
        listing.currency(),
        finalBid,
        1,
        finalBid,
        buyerTotal,
        sellerReceive,
        fee,
        tax,
        tradeStatus,
        refundDeadline,
        deliveryMode == DeliveryMode.CLAIM ? 0 : cooldownSeconds);
  }

  private BidResult placeBidInTransaction(
      Connection connection,
      long bidderUserId,
      long listingId,
      long bidAmount,
      String idempotencyKey) throws SQLException {
    ExistingBid existingBid = readExistingBid(connection, bidderUserId, idempotencyKey);
    if (existingBid != null) {
      if (existingBid.listingId() != listingId) {
        throw new ServiceException("idempotency_conflict", "Idempotency key is already used by another listing");
      }
      MarketListing listing = readListingForUpdate(connection, listingId);
      MarketAlgorithmRegistry.AuctionAlgorithmType algorithmType = listing.auctionAlgorithmType();
      boolean sealedBid = MarketAlgorithmRegistry.sealedBid(algorithmType);
      long openingBid = listing.auctionStartPrice() == null ? Math.max(1L, listing.price()) : Math.max(1L, listing.auctionStartPrice());
      long currentHighestBid = listing.auctionHighestBid() == null
          ? openingBid
          : listing.auctionHighestBid();
      long minIncrement = listing.auctionMinIncrement() == null ? 1L : Math.max(1L, listing.auctionMinIncrement());
      Long minimumRequiredBid = sealedBid
          ? openingBid
          : (listing.auctionHighestBid() == null ? currentHighestBid : safeAddPositive(currentHighestBid, minIncrement));
      return new BidResult(
          BidState.EXISTING,
          existingBid.bidId(),
          listing.id(),
          listing.currency(),
          existingBid.bidAmount(),
          currentHighestBid,
          null,
          null,
          listing.auctionEndAt(),
          algorithmType.name(),
          sealedBid,
          minimumRequiredBid);
    }

    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!"ACTIVE".equalsIgnoreCase(listing.status())) {
      throw new ServiceException("listing_unavailable", "Listing is unavailable");
    }
    if (!listing.isAuction()) {
      throw new ServiceException("invalid_trade_mode", "This listing does not accept auction bids");
    }
    MarketAlgorithmRegistry.AuctionAlgorithmType algorithmType = listing.auctionAlgorithmType();
    if (!MarketAlgorithmRegistry.supportsBid(algorithmType)) {
      throw new ServiceException("auction_only_buy", "This auction uses direct buy instead of manual bidding");
    }
    if (listing.sellerUserId() == bidderUserId) {
      throw new ServiceException("invalid_bid", "You cannot bid your own listing");
    }
    if (listing.auctionEndAt() == null || !listing.auctionEndAt().isAfter(LocalDateTime.now())) {
      throw new ServiceException("auction_closed", "This auction has ended");
    }

    boolean sealedBid = MarketAlgorithmRegistry.sealedBid(algorithmType);
    long openingBid = listing.auctionStartPrice() == null ? Math.max(1L, listing.price()) : Math.max(1L, listing.auctionStartPrice());
    long currentHighestBid = listing.auctionHighestBid() == null ? openingBid : listing.auctionHighestBid();
    long minIncrement = listing.auctionMinIncrement() == null ? 1L : Math.max(1L, listing.auctionMinIncrement());
    long requiredMinimum = sealedBid
        ? openingBid
        : (listing.auctionHighestBid() == null ? currentHighestBid : safeAddPositive(currentHighestBid, minIncrement));
    if (bidAmount < requiredMinimum) {
      throw new ServiceException("bid_too_low", "Bid must be at least " + requiredMinimum);
    }

    BoundUser bidder = readBoundUserById(connection, bidderUserId, true);
    String holdBizId = "mkt-bid-hold:" + listing.id() + ":" + idempotencyKey;
    walletService.applyDelta(
        connection,
        bidder.userId(),
        listing.currency(),
        -bidAmount,
        "MARKET_BID_HOLD",
        holdBizId,
        true);

    long bidId = insertMarketBid(connection, listing, bidder, bidAmount, idempotencyKey, sealedBid ? "SEALED" : "LEADING");
    Long previousHighestBid = listing.auctionHighestBid();
    Long previousHighestBidderUserId = listing.auctionHighestBidderUserId();
    UUID previousHighestBidderUuid = listing.auctionHighestBidderUuid();
    Long previousHighestBidId = listing.auctionHighestBidId();
    LocalDateTime resolvedAuctionEndAt = listing.auctionEndAt();

    if (!sealedBid) {
      if (previousHighestBidId != null && previousHighestBid != null && previousHighestBid > 0L
          && previousHighestBidderUserId != null) {
        String refundBizId = "mkt-bid-refund:" + listing.id() + ":" + previousHighestBidId;
        walletService.applyDelta(
            connection,
            previousHighestBidderUserId,
            listing.currency(),
            previousHighestBid,
            "MARKET_BID_REFUND",
            refundBizId,
            false);
        markBidStatus(
            connection,
            previousHighestBidId,
            "OUTBID",
            true,
            false);
      }

      LocalDateTime updatedAuctionPublicEndAt = listing.auctionPublicEndAt();
      LocalDateTime updatedAuctionEndAt = listing.auctionEndAt();
      if (algorithmType == MarketAlgorithmRegistry.AuctionAlgorithmType.ENGLISH_AUCTION_V1) {
        JsonObject auctionParams = MarketAlgorithmRegistry.parseParams(listing.auctionParamsJson());
        updatedAuctionEndAt = maybeApplyAntiSnipingExtension(
            listing.auctionEndAt(),
            auctionParams,
            LocalDateTime.now());
        updatedAuctionPublicEndAt = updatedAuctionEndAt;
      }
      resolvedAuctionEndAt = updatedAuctionEndAt;

      String updateSql = """
          UPDATE market_listings
          SET auction_highest_bid = ?,
              auction_highest_bidder_user_id = ?,
              auction_highest_bidder_uuid = ?,
              auction_highest_bid_id = ?,
              auction_last_bid_at = NOW(),
              auction_public_end_at = ?,
              auction_end_at = ?,
              price = ?
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
        statement.setLong(1, bidAmount);
        statement.setLong(2, bidder.userId());
        statement.setString(3, bidder.boundUuid().toString());
        statement.setLong(4, bidId);
        if (updatedAuctionPublicEndAt == null) {
          statement.setTimestamp(5, null);
        } else {
          statement.setTimestamp(5, Timestamp.valueOf(updatedAuctionPublicEndAt));
        }
        if (updatedAuctionEndAt == null) {
          statement.setTimestamp(6, null);
        } else {
          statement.setTimestamp(6, Timestamp.valueOf(updatedAuctionEndAt));
        }
        statement.setLong(7, bidAmount);
        statement.setLong(8, listing.id());
        statement.executeUpdate();
      }

      if (previousHighestBidderUuid != null
          && (previousHighestBidderUserId == null || previousHighestBidderUserId != bidder.userId())) {
        notifyPlayerAsync(
            previousHighestBidderUuid,
            "You were outbid on auction #"
                + listing.id()
                + ". Previous highest bid was "
                + previousHighestBid
                + " "
                + listing.currency().name()
                + ".");
      }
    } else {
      String updateSql = """
          UPDATE market_listings
          SET auction_last_bid_at = NOW()
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
        statement.setLong(1, listing.id());
        statement.executeUpdate();
      }
      previousHighestBid = null;
      previousHighestBidderUserId = null;
      resolvedAuctionEndAt = listing.auctionEndAt();
    }

    return new BidResult(
        BidState.CREATED,
        bidId,
        listing.id(),
        listing.currency(),
        bidAmount,
        sealedBid ? currentHighestBid : bidAmount,
        previousHighestBid,
        previousHighestBidderUserId,
        resolvedAuctionEndAt,
        algorithmType.name(),
        sealedBid,
        requiredMinimum);
  }

  private LocalDateTime maybeApplyAntiSnipingExtension(
      LocalDateTime auctionEndAt,
      JsonObject auctionParams,
      LocalDateTime now) {
    if (auctionEndAt == null) {
      return null;
    }
    LocalDateTime referenceNow = now == null ? LocalDateTime.now() : now;
    if (!auctionEndAt.isAfter(referenceNow)) {
      return auctionEndAt;
    }
    long antiSnipingWindowSeconds = Math.max(
        0L,
        MarketAlgorithmRegistry.getLongParam(
            auctionParams,
            ANTI_SNIPING_WINDOW_KEY,
            DEFAULT_ANTI_SNIPING_WINDOW_SECONDS));
    long antiSnipingExtendSeconds = Math.max(
        0L,
        MarketAlgorithmRegistry.getLongParam(
            auctionParams,
            ANTI_SNIPING_EXTEND_KEY,
            DEFAULT_ANTI_SNIPING_EXTEND_SECONDS));
    if (antiSnipingWindowSeconds <= 0L || antiSnipingExtendSeconds <= 0L) {
      return auctionEndAt;
    }

    LocalDateTime triggerTime = auctionEndAt.minusSeconds(antiSnipingWindowSeconds);
    if (referenceNow.isBefore(triggerTime)) {
      return auctionEndAt;
    }
    return auctionEndAt.plusSeconds(antiSnipingExtendSeconds);
  }

  private ExistingBid readExistingBid(Connection connection, long bidderUserId, String idempotencyKey)
      throws SQLException {
    String sql = """
        SELECT id, listing_id, bid_amount, status
        FROM market_bids
        WHERE bidder_user_id = ? AND idempotency_key = ?
        FOR UPDATE
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, bidderUserId);
      statement.setString(2, idempotencyKey);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return new ExistingBid(
            resultSet.getLong("id"),
            resultSet.getLong("listing_id"),
            resultSet.getLong("bid_amount"),
            resultSet.getString("status"));
      }
    }
  }

  private long insertMarketBid(
      Connection connection,
      MarketListing listing,
      BoundUser bidder,
      long bidAmount,
      String idempotencyKey,
      String status) throws SQLException {
    String sql = """
        INSERT INTO market_bids (
          listing_id, bidder_user_id, bidder_uuid, bid_amount, status, idempotency_key
        )
        VALUES (?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, listing.id());
      statement.setLong(2, bidder.userId());
      statement.setString(3, bidder.boundUuid().toString());
      statement.setLong(4, bidAmount);
      statement.setString(5, status);
      statement.setString(6, idempotencyKey);
      statement.executeUpdate();
      try (ResultSet keyResult = statement.getGeneratedKeys()) {
        if (!keyResult.next()) {
          throw new IllegalStateException("Could not read generated market bid id");
        }
        return keyResult.getLong(1);
      }
    }
  }

  private void markBidStatus(
      Connection connection,
      long bidId,
      String status,
      boolean markRefunded,
      boolean markSettled) throws SQLException {
    String sql = """
        UPDATE market_bids
        SET status = ?,
            outbid_at = CASE WHEN ? THEN NOW() ELSE outbid_at END,
            refunded_at = CASE WHEN ? THEN NOW() ELSE refunded_at END,
            settled_at = CASE WHEN ? THEN NOW() ELSE settled_at END
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, status);
      statement.setBoolean(2, "OUTBID".equalsIgnoreCase(status));
      statement.setBoolean(3, markRefunded);
      statement.setBoolean(4, markSettled);
      statement.setLong(5, bidId);
      statement.executeUpdate();
    }
  }

  private boolean hasPendingAuctionBids(Connection connection, long listingId) throws SQLException {
    String sql = """
        SELECT COUNT(*) AS total
        FROM market_bids
        WHERE listing_id = ?
          AND status IN ('LEADING', 'SEALED')
          AND refunded_at IS NULL
          AND settled_at IS NULL
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, listingId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return false;
        }
        return resultSet.getLong("total") > 0L;
      }
    }
  }

  private void refundAuctionBidsIfPresent(Connection connection, MarketListing listing, String reasonTag)
      throws SQLException {
    if (!listing.isAuction()) {
      return;
    }

    String pendingSql = """
        SELECT id, bidder_user_id, bidder_uuid, bid_amount
        FROM market_bids
        WHERE listing_id = ?
          AND status IN ('LEADING', 'SEALED')
          AND refunded_at IS NULL
          AND settled_at IS NULL
        FOR UPDATE
        """;
    List<AuctionBidRefund> refunds = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(pendingSql)) {
      statement.setLong(1, listing.id());
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String bidderUuidRaw = resultSet.getString("bidder_uuid");
          refunds.add(new AuctionBidRefund(
              resultSet.getLong("id"),
              resultSet.getLong("bidder_user_id"),
              bidderUuidRaw == null || bidderUuidRaw.isBlank() ? null : UUID.fromString(bidderUuidRaw),
              resultSet.getLong("bid_amount")));
        }
      }
    }

    for (AuctionBidRefund refund : refunds) {
      if (refund.bidAmount() > 0L) {
        String refundBizId = "mkt-bid-refund:" + listing.id() + ":" + refund.bidId() + ":" + reasonTag;
        walletService.applyDelta(
            connection,
            refund.bidderUserId(),
            listing.currency(),
            refund.bidAmount(),
            "MARKET_BID_REFUND",
            refundBizId,
            false);
      }
      markBidStatus(connection, refund.bidId(), "REFUNDED", true, false);
      if (refund.bidderUuid() != null) {
        notifyPlayerAsync(
            refund.bidderUuid(),
            "Auction #" + listing.id() + " was reset or cancelled, and your frozen bid funds were returned.");
      }
    }

    String clearSql = """
        UPDATE market_listings
        SET auction_highest_bid = NULL,
            auction_highest_bidder_user_id = NULL,
            auction_highest_bidder_uuid = NULL,
            auction_highest_bid_id = NULL,
            auction_last_bid_at = NULL,
            price = CASE WHEN auction_start_price IS NULL THEN price ELSE auction_start_price END
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(clearSql)) {
      statement.setLong(1, listing.id());
      statement.executeUpdate();
    }
  }

  private List<AuctionSettlementNotice> settleDueAuctions(Connection connection) throws SQLException {
    List<Long> dueListingIds = new ArrayList<>();
    String dueSql = """
        SELECT id
        FROM market_listings
        WHERE trade_mode = 'AUCTION'
          AND status = 'ACTIVE'
          AND auction_end_at IS NOT NULL
          AND auction_end_at <= NOW()
        ORDER BY auction_end_at ASC
        LIMIT ?
        FOR UPDATE
        """;
    try (PreparedStatement statement = connection.prepareStatement(dueSql)) {
      statement.setInt(1, AUCTION_SETTLE_BATCH_LIMIT);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          dueListingIds.add(resultSet.getLong("id"));
        }
      }
    }

    List<AuctionSettlementNotice> notices = new ArrayList<>();
    for (Long listingId : dueListingIds) {
      MarketListing listing = readListingForUpdate(connection, listingId);
      if (!listing.isAuction() || !"ACTIVE".equalsIgnoreCase(listing.status())) {
        continue;
      }
      if (listing.auctionEndAt() == null || listing.auctionEndAt().isAfter(LocalDateTime.now())) {
        continue;
      }

      switch (listing.auctionAlgorithmType()) {
        case DUTCH_AUCTION_V1 -> settleDutchAuctionWithoutBuyer(connection, listing, notices);
        case VICKREY_AUCTION_V1 -> settleVickreyAuction(connection, listing, notices);
        case ENGLISH_AUCTION_V1, CANDLE_AUCTION_V1 -> settleAscendingAuction(connection, listing, notices);
      }
    }
    return notices;
  }

  private void settleDutchAuctionWithoutBuyer(
      Connection connection,
      MarketListing listing,
      List<AuctionSettlementNotice> notices) throws SQLException {
    finalizeAuctionWithoutWinner(
        connection,
        listing,
        notices,
        "auction-expired-dutch",
        "Auction #" + listing.id() + " ended without a buyer, item returned for delivery.");
  }

  private void settleAscendingAuction(
      Connection connection,
      MarketListing listing,
      List<AuctionSettlementNotice> notices) throws SQLException {
    if (listing.auctionHighestBid() == null
        || listing.auctionHighestBid() <= 0L
        || listing.auctionHighestBidderUserId() == null
        || listing.auctionHighestBidId() == null) {
      finalizeAuctionWithoutWinner(
          connection,
          listing,
          notices,
          "auction-expired-no-bid",
          "Auction #" + listing.id() + " ended with no bids, item returned for delivery.");
      return;
    }

    JsonObject auctionParams = MarketAlgorithmRegistry.parseParams(listing.auctionParamsJson());
    long reservePrice = Math.max(0L, MarketAlgorithmRegistry.getLongParam(auctionParams, "reservePrice", 0L));
    if (reservePrice > 0L && listing.auctionHighestBid() < reservePrice) {
      finalizeAuctionWithoutWinner(
          connection,
          listing,
          notices,
          "auction-expired-reserve",
          "Auction #" + listing.id() + " ended below reserve price, item returned for delivery.");
      return;
    }

    BoundUser winner = readBoundUserById(connection, listing.auctionHighestBidderUserId(), true);
    long finalBid = listing.auctionHighestBid();
    long tradeId = insertAuctionSettlementTrade(
        connection,
        listing,
        winner,
        finalBid,
        listing.auctionHighestBidId());
    markAuctionListingSold(connection, listing, winner, finalBid, listing.auctionHighestBidId());
    markBidStatus(connection, listing.auctionHighestBidId(), "WON", false, true);
    refundPendingAuctionLosers(connection, listing, listing.auctionHighestBidId(), "auction-settle");
    enqueueAuctionWinnerDelivery(connection, listing, winner, tradeId);

    notices.add(new AuctionSettlementNotice(
        winner.boundUuid(),
        "You won auction #" + listing.id() + " at "
            + finalBid + " " + listing.currency().name() + ". Delivery is queued."));
    notices.add(new AuctionSettlementNotice(
        listing.sellerUuid(),
        "Your auction #" + listing.id() + " was sold at "
            + finalBid + " " + listing.currency().name() + "."));
  }

  private void settleVickreyAuction(
      Connection connection,
      MarketListing listing,
      List<AuctionSettlementNotice> notices) throws SQLException {
    String bidSql = """
        SELECT id, bidder_user_id, bidder_uuid, bid_amount
        FROM market_bids
        WHERE listing_id = ?
          AND status IN ('SEALED', 'LEADING')
          AND refunded_at IS NULL
          AND settled_at IS NULL
        ORDER BY bid_amount DESC, created_at ASC
        FOR UPDATE
        """;
    List<AuctionBidRefund> bids = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(bidSql)) {
      statement.setLong(1, listing.id());
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String bidderUuidRaw = resultSet.getString("bidder_uuid");
          bids.add(new AuctionBidRefund(
              resultSet.getLong("id"),
              resultSet.getLong("bidder_user_id"),
              bidderUuidRaw == null || bidderUuidRaw.isBlank() ? null : UUID.fromString(bidderUuidRaw),
              resultSet.getLong("bid_amount")));
        }
      }
    }

    if (bids.isEmpty()) {
      finalizeAuctionWithoutWinner(
          connection,
          listing,
          notices,
          "auction-expired-no-bid",
          "Auction #" + listing.id() + " ended with no bids, item returned for delivery.");
      return;
    }

    JsonObject auctionParams = MarketAlgorithmRegistry.parseParams(listing.auctionParamsJson());
    long reservePrice = Math.max(0L, MarketAlgorithmRegistry.getLongParam(auctionParams, "reservePrice", 0L));
    AuctionBidRefund winnerBid = bids.get(0);
    if (reservePrice > 0L && winnerBid.bidAmount() < reservePrice) {
      finalizeAuctionWithoutWinner(
          connection,
          listing,
          notices,
          "auction-expired-reserve",
          "Auction #" + listing.id() + " ended below reserve price, item returned for delivery.");
      return;
    }

    long openingBid = listing.auctionStartPrice() == null ? Math.max(1L, listing.price()) : Math.max(1L, listing.auctionStartPrice());
    long secondPrice = bids.size() > 1 ? bids.get(1).bidAmount() : openingBid;
    long finalBid = Math.max(openingBid, secondPrice);
    if (reservePrice > 0L) {
      finalBid = Math.max(finalBid, reservePrice);
    }
    finalBid = Math.min(finalBid, winnerBid.bidAmount());

    BoundUser winner = readBoundUserById(connection, winnerBid.bidderUserId(), true);
    long tradeId = insertAuctionSettlementTrade(connection, listing, winner, finalBid, winnerBid.bidId());
    markAuctionListingSold(connection, listing, winner, finalBid, winnerBid.bidId());

    long refundDifference = Math.max(0L, winnerBid.bidAmount() - finalBid);
    if (refundDifference > 0L) {
      String refundBizId = "mkt-bid-refund:" + listing.id() + ":" + winnerBid.bidId() + ":vickrey-diff";
      walletService.applyDelta(
          connection,
          winner.userId(),
          listing.currency(),
          refundDifference,
          "MARKET_BID_REFUND",
          refundBizId,
          false);
    }
    markBidStatus(connection, winnerBid.bidId(), "WON", refundDifference > 0L, true);
    refundPendingAuctionLosers(connection, listing, winnerBid.bidId(), "auction-settle-vickrey");
    enqueueAuctionWinnerDelivery(connection, listing, winner, tradeId);

    notices.add(new AuctionSettlementNotice(
        winner.boundUuid(),
        "You won auction #"
            + listing.id()
            + " with bid "
            + winnerBid.bidAmount()
            + " "
            + listing.currency().name()
            + ", and the final clearing price is "
            + finalBid
            + "."));
    notices.add(new AuctionSettlementNotice(
        listing.sellerUuid(),
        "Your auction #" + listing.id() + " was sold (Vickrey) at "
            + finalBid + " " + listing.currency().name() + "."));
  }

  private void finalizeAuctionWithoutWinner(
      Connection connection,
      MarketListing listing,
      List<AuctionSettlementNotice> notices,
      String reasonTag,
      String sellerMessage) throws SQLException {
    if (hasPendingAuctionBids(connection, listing.id())) {
      refundAuctionBidsIfPresent(connection, listing, reasonTag);
      listing = readListingForUpdate(connection, listing.id());
    }

    String noBidSql = """
        UPDATE market_listings
        SET status = 'UNLISTED',
            unlisted_at = NOW(),
            paused_at = NULL,
            auction_highest_bid = NULL,
            auction_highest_bidder_user_id = NULL,
            auction_highest_bidder_uuid = NULL,
            auction_highest_bid_id = NULL,
            auction_last_bid_at = NULL,
            price = CASE WHEN auction_start_price IS NULL THEN price ELSE auction_start_price END
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(noBidSql)) {
      statement.setLong(1, listing.id());
      statement.executeUpdate();
    }

    int returnQuantity = Math.max(0, listing.quantity());
    if (returnQuantity > 0) {
      enqueueMarketItemDelivery(
          connection,
          listing.id(),
          null,
          listing.sellerUserId(),
          listing.sellerUuid(),
          listing.rawItemBlob(),
          returnQuantity,
          DeliveryType.UNLIST,
          "PENDING",
          LocalDateTime.now());
    }
    notices.add(new AuctionSettlementNotice(listing.sellerUuid(), sellerMessage));
  }

  private void refundPendingAuctionLosers(
      Connection connection,
      MarketListing listing,
      Long winnerBidId,
      String reasonTag) throws SQLException {
    String pendingSql = """
        SELECT id, bidder_user_id, bidder_uuid, bid_amount
        FROM market_bids
        WHERE listing_id = ?
          AND status IN ('LEADING', 'SEALED')
          AND refunded_at IS NULL
          AND settled_at IS NULL
          AND (? IS NULL OR id <> ?)
        FOR UPDATE
        """;
    List<AuctionBidRefund> pending = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(pendingSql)) {
      statement.setLong(1, listing.id());
      if (winnerBidId == null) {
        statement.setObject(2, null);
        statement.setObject(3, null);
      } else {
        statement.setLong(2, winnerBidId);
        statement.setLong(3, winnerBidId);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String bidderUuidRaw = resultSet.getString("bidder_uuid");
          pending.add(new AuctionBidRefund(
              resultSet.getLong("id"),
              resultSet.getLong("bidder_user_id"),
              bidderUuidRaw == null || bidderUuidRaw.isBlank() ? null : UUID.fromString(bidderUuidRaw),
              resultSet.getLong("bid_amount")));
        }
      }
    }

    for (AuctionBidRefund bid : pending) {
      if (bid.bidAmount() > 0L) {
        String refundBizId = "mkt-bid-refund:" + listing.id() + ":" + bid.bidId() + ":" + reasonTag;
        walletService.applyDelta(
            connection,
            bid.bidderUserId(),
            listing.currency(),
            bid.bidAmount(),
            "MARKET_BID_REFUND",
            refundBizId,
            false);
      }
      markBidStatus(connection, bid.bidId(), "OUTBID", true, false);
      if (bid.bidderUuid() != null) {
        notifyPlayerAsync(
            bid.bidderUuid(),
            "Auction #" + listing.id() + " has ended, you did not win, and your frozen funds were returned.");
      }
    }
  }

  private long insertAuctionSettlementTrade(
      Connection connection,
      MarketListing listing,
      BoundUser winner,
      long finalBid,
      long winningBidId) throws SQLException {
    PluginSettings.MarketEconomySettings marketEconomy = settingsSupplier.get().economySettings().marketSettings();
    long fee = calculatePercent(finalBid, marketEconomy.tradeFeePercent());
    long sellerReceive = Math.max(0L, finalBid - fee);
    String idempotencyKey = "auction-settle:" + listing.id() + ":" + winningBidId;
    return insertTrade(
        connection,
        listing.id(),
        winner.userId(),
        listing.sellerUserId(),
        listing.currency(),
        finalBid,
        1,
        finalBid,
        finalBid,
        sellerReceive,
        fee,
        0L,
        idempotencyKey,
        "PENDING",
        null);
  }

  private void markAuctionListingSold(
      Connection connection,
      MarketListing listing,
      BoundUser winner,
      long finalBid,
      Long winningBidId) throws SQLException {
    String soldSql = """
        UPDATE market_listings
        SET quantity = 0,
            status = 'SOLD',
            buyer_user_id = ?,
            buyer_uuid = ?,
            sold_at = NOW(),
            price = ?,
            auction_highest_bid = ?,
            auction_highest_bidder_user_id = ?,
            auction_highest_bidder_uuid = ?,
            auction_highest_bid_id = ?,
            auction_last_bid_at = NOW()
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(soldSql)) {
      statement.setLong(1, winner.userId());
      statement.setString(2, winner.boundUuid().toString());
      statement.setLong(3, finalBid);
      statement.setLong(4, finalBid);
      statement.setLong(5, winner.userId());
      statement.setString(6, winner.boundUuid().toString());
      statement.setObject(7, winningBidId);
      statement.setLong(8, listing.id());
      statement.executeUpdate();
    }
  }

  private void enqueueAuctionWinnerDelivery(
      Connection connection,
      MarketListing listing,
      BoundUser winner,
      long tradeId) throws SQLException {
    int deliveryQuantity = Math.max(0, listing.quantity());
    if (deliveryQuantity <= 0) {
      return;
    }
    enqueueMarketItemDelivery(
        connection,
        listing.id(),
        tradeId,
        winner.userId(),
        winner.boundUuid(),
        listing.rawItemBlob(),
        deliveryQuantity,
        DeliveryType.SALE,
        "PENDING",
        LocalDateTime.now());
  }

  private int applyDynamicPriceDecay(Connection connection) throws SQLException {
    String selectSql = """
        SELECT id, price,
               dynamic_algorithm, dynamic_params_json,
               dynamic_base_price, dynamic_floor_price, dynamic_cap_price, dynamic_price_step,
               dynamic_demand_score
        FROM market_listings
        WHERE trade_mode = 'DIRECT'
          AND dynamic_pricing_enabled = TRUE
          AND status = 'ACTIVE'
          AND dynamic_demand_score > 0
        FOR UPDATE
        """;
    List<DynamicDecayTarget> targets = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(selectSql);
         ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        targets.add(new DynamicDecayTarget(
            resultSet.getLong("id"),
            resultSet.getLong("price"),
            resultSet.getString("dynamic_algorithm"),
            resultSet.getString("dynamic_params_json"),
            (Long) resultSet.getObject("dynamic_base_price"),
            (Long) resultSet.getObject("dynamic_floor_price"),
            (Long) resultSet.getObject("dynamic_cap_price"),
            (Long) resultSet.getObject("dynamic_price_step"),
            resultSet.getLong("dynamic_demand_score")));
      }
    }

    if (targets.isEmpty()) {
      return 0;
    }

    String updateSql = """
        UPDATE market_listings
        SET dynamic_demand_score = ?, price = ?
        WHERE id = ?
        """;
    int updated = 0;
    try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
      for (DynamicDecayTarget target : targets) {
        MarketAlgorithmRegistry.DynamicAlgorithmType algorithmType = MarketAlgorithmRegistry.DynamicAlgorithmType
            .fromRaw(target.dynamicAlgorithm());
        JsonObject params = MarketAlgorithmRegistry.parseParams(target.dynamicParamsJson());
        long basePrice = target.dynamicBasePrice() == null ? Math.max(1L, target.currentPrice()) : Math.max(1L, target.dynamicBasePrice());
        long step = target.dynamicPriceStep() == null ? 1L : Math.max(1L, target.dynamicPriceStep());
        long nextDemand = MarketAlgorithmRegistry.computeDemandAfterDecay(target.dynamicDemandScore(), DYNAMIC_DECAY_STEP);
        long nextPrice = MarketAlgorithmRegistry.computeDynamicPrice(
            algorithmType,
            basePrice,
            nextDemand,
            step,
            target.dynamicFloorPrice(),
            target.dynamicCapPrice(),
            params);

        statement.setLong(1, nextDemand);
        statement.setLong(2, nextPrice);
        statement.setLong(3, target.listingId());
        statement.addBatch();
      }
      int[] counts = statement.executeBatch();
      for (int count : counts) {
        if (count > 0) {
          updated += count;
        }
      }
    }
    return updated;
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
    if (listing.isAuction()) {
      refundAuctionBidsIfPresent(connection, listing, "seller-unlist");
      listing = readListingForUpdate(connection, listing.id());
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
    listing = readListingForUpdate(connection, listing.id());
    if (listing.marketSide() == MarketSide.BUY) {
      refundBuyEscrowIfNeeded(connection, listing, "seller-unlist");
      return new UnlistResult(listing.id(), listing.currency(), listing.price(), listing.quantity());
    }
    if (listing.quantity() > 0) {
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
    }
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
    if (listing.marketSide() == MarketSide.BUY) {
      throw new ServiceException("buy_requires_fixed_price", "BUY listing price is fixed after creation");
    }
    MarketLimitationService.Decision limitationDecision = evaluateLimitationDecision(
        listing.sellerUuid(),
        listing.marketSide(),
        listing.tradeMode(),
        listing.currency(),
        listing.tagCode(),
        listing.itemMaterial(),
        listing.rawItemBlob(),
        listing.itemMetaJson());
    applyLimitationDecision(
        limitationDecision,
        listing.marketSide(),
        listing.tradeMode(),
        listing.currency(),
        listing.tagCode());

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
      String tagCodeRaw,
      String remark,
      String displayNameOverride,
      String displayMaterial,
      String displayIconPath,
      Integer supplyBatchSize,
      Integer supplyMaxStock,
      String tradeModeRaw,
      Boolean dynamicPricingEnabled,
      String dynamicAlgorithmRaw,
      String dynamicParamsJson,
      Long dynamicBasePrice,
      Long dynamicFloorPrice,
      Long dynamicCapPrice,
      Long dynamicPriceStep,
      String auctionAlgorithmRaw,
      String auctionParamsJson,
      Long auctionStartPrice,
      Long auctionMinIncrement,
      LocalDateTime auctionEndAt) throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!"ACTIVE".equalsIgnoreCase(listing.status()) && !"PAUSED".equalsIgnoreCase(listing.status())) {
      throw new ServiceException("listing_unavailable", "Supply mode is not enabled for this listing");
    }
    if (listing.sellerUserId() != sellerUserId) {
      throw new ServiceException("forbidden", "Only the owner can update listing settings");
    }
    if (listing.marketSide() == MarketSide.BUY && price != listing.price()) {
      throw new ServiceException("buy_requires_fixed_price", "BUY listing price is fixed after creation");
    }
    String normalizedDisplayNameOverride = normalizeDisplayNameOverride(displayNameOverride);
    String normalizedDisplayMaterial = normalizeDisplayMaterial(displayMaterial);
    String normalizedDisplayIconPath = normalizeDisplayIconPath(displayIconPath);
    String normalizedRequestedTag = normalizeTagCode(tagCodeRaw);
    if (normalizedRequestedTag == null) {
      normalizedRequestedTag = listing.tagCode();
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

    TradeMode tradeMode = resolveTradeMode(tradeModeRaw, listing.tradeMode());
    if (listing.marketSide() == MarketSide.BUY) {
      if (listing.sourceMode() != SupplyMode.MANUAL) {
        throw new ServiceException("buy_requires_manual_source", "BUY listings must use MANUAL source mode");
      }
      if (tradeMode != TradeMode.DIRECT) {
        throw new ServiceException("buy_requires_direct_mode", "BUY listings only support DIRECT trade mode");
      }
      boolean dynamicConfigTouched = Boolean.TRUE.equals(dynamicPricingEnabled)
          || (dynamicAlgorithmRaw != null && !dynamicAlgorithmRaw.isBlank())
          || dynamicParamsJson != null
          || dynamicBasePrice != null
          || dynamicFloorPrice != null
          || dynamicCapPrice != null
          || dynamicPriceStep != null;
      if (dynamicConfigTouched) {
        throw new ServiceException("buy_requires_fixed_price", "BUY listings do not support dynamic pricing");
      }
      boolean auctionConfigTouched = (auctionAlgorithmRaw != null && !auctionAlgorithmRaw.isBlank())
          || auctionParamsJson != null
          || auctionStartPrice != null
          || auctionMinIncrement != null
          || auctionEndAt != null;
      if (auctionConfigTouched) {
        throw new ServiceException("buy_requires_direct_mode", "BUY listings do not support auction parameters");
      }
    }
    if (tradeMode == TradeMode.AUCTION && listing.isSupply()) {
      throw new ServiceException("invalid_trade_mode", "Supply listings do not support auction mode");
    }

    MarketLimitationService.Decision limitationDecision = evaluateLimitationDecision(
        listing.sellerUuid(),
        listing.marketSide(),
        tradeMode,
        currency,
        normalizedRequestedTag,
        listing.itemMaterial(),
        listing.rawItemBlob(),
        listing.itemMetaJson());
    applyLimitationDecision(
        limitationDecision,
        listing.marketSide(),
        tradeMode,
        currency,
        normalizedRequestedTag);
    String resolvedTag = limitationDecision.forcedTag() == null
        ? normalizedRequestedTag
        : limitationDecision.forcedTag();
    MarketTagService.TagAssignment tagAssignment = marketTagService.resolveTag(
        resolvedTag,
        listing.rawItemBlob(),
        listing.itemMetaJson(),
        listing.itemMaterial());
    marketTagService.syncDictionary(connection);
    if (tradeMode != TradeMode.AUCTION && listing.isAuction()) {
      refundAuctionBidsIfPresent(connection, listing, "mode-switch");
      listing = readListingForUpdate(connection, listing.id());
    }

    MarketAlgorithmRegistry.DynamicAlgorithmType dynamicAlgorithmType = MarketAlgorithmRegistry.DynamicAlgorithmType
        .fromRaw(dynamicAlgorithmRaw == null || dynamicAlgorithmRaw.isBlank()
            ? listing.dynamicAlgorithm()
            : dynamicAlgorithmRaw);
    JsonObject normalizedDynamicParams = dynamicParamsJson == null
        ? MarketAlgorithmRegistry.parseParams(listing.dynamicParamsJson())
        : MarketAlgorithmRegistry.parseParams(dynamicParamsJson);

    boolean normalizedDynamicEnabled = dynamicPricingEnabled == null
        ? listing.dynamicPricingEnabled()
        : dynamicPricingEnabled;
    Long normalizedDynamicBasePrice = normalizeOptionalPositive(dynamicBasePrice, "invalid_dynamic_base");
    Long normalizedDynamicFloorPrice = normalizeOptionalPositive(dynamicFloorPrice, "invalid_dynamic_floor");
    Long normalizedDynamicCapPrice = normalizeOptionalPositive(dynamicCapPrice, "invalid_dynamic_cap");
    Long normalizedDynamicPriceStep = normalizeOptionalPositive(dynamicPriceStep, "invalid_dynamic_step");
    long normalizedDynamicDemandScore = Math.max(0L, listing.dynamicDemandScore());

    MarketAlgorithmRegistry.AuctionAlgorithmType auctionAlgorithmType = MarketAlgorithmRegistry.AuctionAlgorithmType
        .fromRaw(auctionAlgorithmRaw == null || auctionAlgorithmRaw.isBlank()
            ? listing.auctionAlgorithm()
            : auctionAlgorithmRaw);
    JsonObject normalizedAuctionParams = auctionParamsJson == null
        ? MarketAlgorithmRegistry.parseParams(listing.auctionParamsJson())
        : MarketAlgorithmRegistry.parseParams(auctionParamsJson);
    Long normalizedAuctionStartPrice = normalizeOptionalPositive(auctionStartPrice, "invalid_auction_start");
    Long normalizedAuctionMinIncrement = normalizeOptionalPositive(auctionMinIncrement, "invalid_auction_increment");
    LocalDateTime normalizedAuctionStartedAt = listing.auctionStartedAt();
    LocalDateTime normalizedAuctionPublicEndAt = listing.auctionPublicEndAt();
    LocalDateTime normalizedAuctionEndAt = auctionEndAt;
    Long normalizedAuctionHighestBid = listing.auctionHighestBid();
    Long normalizedAuctionHighestBidderUserId = listing.auctionHighestBidderUserId();
    UUID normalizedAuctionHighestBidderUuid = listing.auctionHighestBidderUuid();
    Long normalizedAuctionHighestBidId = listing.auctionHighestBidId();
    LocalDateTime normalizedAuctionLastBidAt = listing.auctionLastBidAt();
    if (listing.marketSide() == MarketSide.BUY) {
      normalizedDynamicEnabled = false;
      normalizedDynamicBasePrice = null;
      normalizedDynamicFloorPrice = null;
      normalizedDynamicCapPrice = null;
      normalizedDynamicPriceStep = null;
      normalizedDynamicDemandScore = 0L;
    }

    long effectivePrice = price;
    if (tradeMode == TradeMode.AUCTION) {
      if (listing.quantity() <= 0) {
        throw new ServiceException("listing_empty", "Auction listing must have available quantity");
      }

      boolean pendingBids = hasPendingAuctionBids(connection, listing.id());
      boolean hasExplicitAuctionChange = auctionAlgorithmRaw != null
          || auctionParamsJson != null
          || auctionStartPrice != null
          || auctionMinIncrement != null
          || auctionEndAt != null;
      if (pendingBids && hasExplicitAuctionChange) {
        refundAuctionBidsIfPresent(connection, listing, "settings-reset");
        listing = readListingForUpdate(connection, listing.id());
        normalizedAuctionHighestBid = listing.auctionHighestBid();
        normalizedAuctionHighestBidderUserId = listing.auctionHighestBidderUserId();
        normalizedAuctionHighestBidderUuid = listing.auctionHighestBidderUuid();
        normalizedAuctionHighestBidId = listing.auctionHighestBidId();
        normalizedAuctionLastBidAt = listing.auctionLastBidAt();
      }

      normalizedDynamicEnabled = false;
      normalizedDynamicBasePrice = null;
      normalizedDynamicFloorPrice = null;
      normalizedDynamicCapPrice = null;
      normalizedDynamicPriceStep = null;
      normalizedDynamicDemandScore = 0L;

      if (normalizedAuctionStartPrice == null) {
        normalizedAuctionStartPrice = listing.auctionStartPrice() != null
            ? listing.auctionStartPrice()
            : price;
      }
      if (normalizedAuctionStartPrice == null || normalizedAuctionStartPrice <= 0L) {
        throw new ServiceException("invalid_auction_start", "Auction start price must be positive");
      }

      LocalDateTime now = LocalDateTime.now();
      switch (auctionAlgorithmType) {
        case ENGLISH_AUCTION_V1 -> {
          if (normalizedAuctionMinIncrement == null) {
            normalizedAuctionMinIncrement = listing.auctionMinIncrement() != null
                ? Math.max(1L, listing.auctionMinIncrement())
                : 1L;
          }
          if (normalizedAuctionEndAt == null) {
            normalizedAuctionEndAt = listing.auctionEndAt();
          }
          if (normalizedAuctionMinIncrement == null || normalizedAuctionMinIncrement <= 0L) {
            throw new ServiceException("invalid_auction_increment", "Auction min increment must be positive");
          }
          if (normalizedAuctionEndAt == null || !normalizedAuctionEndAt.isAfter(now.plusSeconds(MIN_AUCTION_DURATION_SECONDS))) {
            throw new ServiceException("invalid_auction_end", "Auction end time must be at least 30 seconds later");
          }
          long antiSnipingWindowSeconds = Math.max(
              0L,
              MarketAlgorithmRegistry.getLongParam(
                  normalizedAuctionParams,
                  ANTI_SNIPING_WINDOW_KEY,
                  DEFAULT_ANTI_SNIPING_WINDOW_SECONDS));
          long antiSnipingExtendSeconds = Math.max(
              0L,
              MarketAlgorithmRegistry.getLongParam(
                  normalizedAuctionParams,
                  ANTI_SNIPING_EXTEND_KEY,
                  DEFAULT_ANTI_SNIPING_EXTEND_SECONDS));
          normalizedAuctionParams.addProperty(ANTI_SNIPING_WINDOW_KEY, antiSnipingWindowSeconds);
          normalizedAuctionParams.addProperty(ANTI_SNIPING_EXTEND_KEY, antiSnipingExtendSeconds);
          normalizedAuctionPublicEndAt = normalizedAuctionEndAt;
          if (normalizedAuctionStartedAt == null || !listing.isAuction()) {
            normalizedAuctionStartedAt = now;
          }
        }
        case DUTCH_AUCTION_V1 -> {
          long floorPrice = MarketAlgorithmRegistry.getLongParam(normalizedAuctionParams, "floorPrice", 0L);
          long durationSeconds = MarketAlgorithmRegistry.getLongParam(normalizedAuctionParams, "durationSeconds", 0L);
          if (floorPrice <= 0L) {
            throw new ServiceException("invalid_auction_floor", "Dutch auction floor price must be positive");
          }
          if (floorPrice > normalizedAuctionStartPrice) {
            throw new ServiceException("invalid_auction_floor", "Dutch auction floor price cannot exceed start price");
          }
          if (durationSeconds < MIN_AUCTION_DURATION_SECONDS) {
            throw new ServiceException("invalid_auction_end", "Dutch auction duration is too short");
          }
          normalizedAuctionParams.addProperty("floorPrice", floorPrice);
          normalizedAuctionParams.addProperty("durationSeconds", durationSeconds);
          normalizedAuctionMinIncrement = null;
          normalizedAuctionStartedAt = now;
          normalizedAuctionPublicEndAt = now.plusSeconds(durationSeconds);
          normalizedAuctionEndAt = normalizedAuctionPublicEndAt;
          normalizedAuctionHighestBid = null;
          normalizedAuctionHighestBidderUserId = null;
          normalizedAuctionHighestBidderUuid = null;
          normalizedAuctionHighestBidId = null;
          normalizedAuctionLastBidAt = null;
          effectivePrice = MarketAlgorithmRegistry.computeDutchPrice(
              normalizedAuctionStartPrice,
              floorPrice,
              normalizedAuctionStartedAt,
              normalizedAuctionEndAt,
              now);
        }
        case VICKREY_AUCTION_V1 -> {
          normalizedAuctionMinIncrement = null;
          if (normalizedAuctionEndAt == null) {
            normalizedAuctionEndAt = listing.auctionEndAt();
          }
          if (normalizedAuctionEndAt == null || !normalizedAuctionEndAt.isAfter(now.plusSeconds(MIN_AUCTION_DURATION_SECONDS))) {
            throw new ServiceException("invalid_auction_end", "Auction end time must be at least 30 seconds later");
          }
          normalizedAuctionPublicEndAt = normalizedAuctionEndAt;
          if (normalizedAuctionStartedAt == null || !listing.isAuction()) {
            normalizedAuctionStartedAt = now;
          }
          normalizedAuctionHighestBid = null;
          normalizedAuctionHighestBidderUserId = null;
          normalizedAuctionHighestBidderUuid = null;
          normalizedAuctionHighestBidId = null;
          normalizedAuctionLastBidAt = null;
        }
        case CANDLE_AUCTION_V1 -> {
          if (normalizedAuctionMinIncrement == null) {
            normalizedAuctionMinIncrement = listing.auctionMinIncrement() != null
                ? Math.max(1L, listing.auctionMinIncrement())
                : 1L;
          }
          if (normalizedAuctionMinIncrement == null || normalizedAuctionMinIncrement <= 0L) {
            throw new ServiceException("invalid_auction_increment", "Auction min increment must be positive");
          }
          long baseDurationSeconds = MarketAlgorithmRegistry.getLongParam(normalizedAuctionParams, "baseDurationSeconds", 3600L);
          long maxExtensionSeconds = MarketAlgorithmRegistry.getLongParam(normalizedAuctionParams, "maxExtensionSeconds", 1800L);
          if (baseDurationSeconds < MIN_AUCTION_DURATION_SECONDS) {
            throw new ServiceException("invalid_auction_end", "Candle auction base duration is too short");
          }
          if (maxExtensionSeconds < 0L) {
            throw new ServiceException("invalid_auction_end", "Candle auction max extension cannot be negative");
          }
          normalizedAuctionParams.addProperty("baseDurationSeconds", baseDurationSeconds);
          normalizedAuctionParams.addProperty("maxExtensionSeconds", maxExtensionSeconds);
          normalizedAuctionStartedAt = now;
          normalizedAuctionPublicEndAt = now.plusSeconds(baseDurationSeconds);
          normalizedAuctionEndAt = MarketAlgorithmRegistry.computeCandleActualEnd(
              normalizedAuctionPublicEndAt,
              (int) Math.min(Integer.MAX_VALUE, maxExtensionSeconds));
        }
      }

      if (normalizedAuctionHighestBid != null && normalizedAuctionHighestBid > 0L
          && auctionAlgorithmType != MarketAlgorithmRegistry.AuctionAlgorithmType.DUTCH_AUCTION_V1
          && auctionAlgorithmType != MarketAlgorithmRegistry.AuctionAlgorithmType.VICKREY_AUCTION_V1) {
        effectivePrice = normalizedAuctionHighestBid;
      }
      if (auctionAlgorithmType != MarketAlgorithmRegistry.AuctionAlgorithmType.DUTCH_AUCTION_V1
          && (effectivePrice <= 0L || (normalizedAuctionHighestBid == null || normalizedAuctionHighestBid <= 0L))) {
        effectivePrice = normalizedAuctionStartPrice;
      }

      long reservePrice = MarketAlgorithmRegistry.getLongParam(normalizedAuctionParams, "reservePrice", 0L);
      if (reservePrice > 0L && reservePrice < normalizedAuctionStartPrice) {
        normalizedAuctionParams.addProperty("reservePrice", normalizedAuctionStartPrice);
      }
    } else {
      auctionAlgorithmType = MarketAlgorithmRegistry.AuctionAlgorithmType.ENGLISH_AUCTION_V1;
      normalizedAuctionParams = new JsonObject();
      normalizedAuctionStartPrice = null;
      normalizedAuctionMinIncrement = null;
      normalizedAuctionStartedAt = null;
      normalizedAuctionPublicEndAt = null;
      normalizedAuctionEndAt = null;
      normalizedAuctionHighestBid = null;
      normalizedAuctionHighestBidderUserId = null;
      normalizedAuctionHighestBidderUuid = null;
      normalizedAuctionHighestBidId = null;
      normalizedAuctionLastBidAt = null;

      if (normalizedDynamicEnabled) {
        if (normalizedDynamicBasePrice == null) {
          normalizedDynamicBasePrice = listing.dynamicBasePrice() != null
              ? listing.dynamicBasePrice()
              : price;
        }
        if (normalizedDynamicPriceStep == null) {
          normalizedDynamicPriceStep = listing.dynamicPriceStep() != null
              ? Math.max(1L, listing.dynamicPriceStep())
              : 1L;
        }
        if (normalizedDynamicFloorPrice != null && normalizedDynamicCapPrice != null
            && normalizedDynamicFloorPrice > normalizedDynamicCapPrice) {
          throw new ServiceException("invalid_dynamic_range", "Dynamic floor price cannot exceed cap price");
        }
        effectivePrice = MarketAlgorithmRegistry.computeDynamicPrice(
            dynamicAlgorithmType,
            normalizedDynamicBasePrice,
            normalizedDynamicDemandScore,
            normalizedDynamicPriceStep,
            normalizedDynamicFloorPrice,
            normalizedDynamicCapPrice,
            normalizedDynamicParams);
      } else {
        dynamicAlgorithmType = MarketAlgorithmRegistry.DynamicAlgorithmType.LINEAR_DEMAND_V1;
        normalizedDynamicParams = new JsonObject();
        normalizedDynamicBasePrice = null;
        normalizedDynamicFloorPrice = null;
        normalizedDynamicCapPrice = null;
        normalizedDynamicPriceStep = null;
        normalizedDynamicDemandScore = 0L;
      }
    }

    String sql = """
        UPDATE market_listings
        SET price = ?, currency = ?, tag_code = ?, tag_version = ?, remark = ?, display_name_override = ?,
        display_material = ?, display_icon_path = ?, supply_batch_size = ?, supply_max_stock = ?,
        quantity_total = ?, trade_mode = ?, dynamic_pricing_enabled = ?, dynamic_algorithm = ?,
        dynamic_params_json = ?, dynamic_base_price = ?, dynamic_floor_price = ?, dynamic_cap_price = ?,
        dynamic_price_step = ?, dynamic_demand_score = ?, auction_algorithm = ?, auction_params_json = ?,
        auction_start_price = ?, auction_min_increment = ?, auction_started_at = ?, auction_public_end_at = ?,
        auction_end_at = ?, auction_highest_bid = ?, auction_highest_bidder_user_id = ?,
        auction_highest_bidder_uuid = ?, auction_highest_bid_id = ?, auction_last_bid_at = ?
        WHERE id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, effectivePrice);
      statement.setString(2, currency.name());
      statement.setString(3, tagAssignment.code());
      statement.setInt(4, tagAssignment.tagVersion());
      statement.setString(5, remark);
      statement.setString(6, normalizedDisplayNameOverride);
      statement.setString(7, normalizedDisplayMaterial);
      statement.setString(8, normalizedDisplayIconPath);
      if (batch == null) {
        statement.setObject(9, null);
      } else {
        statement.setInt(9, batch);
      }
      if (maxStock == null) {
        statement.setObject(10, null);
      } else {
        statement.setInt(10, maxStock);
      }
      statement.setInt(11, quantityTotal);
      statement.setString(12, tradeMode.name());
      statement.setBoolean(13, normalizedDynamicEnabled);
      statement.setString(14, dynamicAlgorithmType.name());
      statement.setString(15, MarketAlgorithmRegistry.toJson(normalizedDynamicParams));
      statement.setObject(16, normalizedDynamicBasePrice);
      statement.setObject(17, normalizedDynamicFloorPrice);
      statement.setObject(18, normalizedDynamicCapPrice);
      statement.setObject(19, normalizedDynamicPriceStep);
      statement.setLong(20, normalizedDynamicDemandScore);
      statement.setString(21, auctionAlgorithmType.name());
      statement.setString(22, MarketAlgorithmRegistry.toJson(normalizedAuctionParams));
      statement.setObject(23, normalizedAuctionStartPrice);
      statement.setObject(24, normalizedAuctionMinIncrement);
      if (normalizedAuctionStartedAt == null) {
        statement.setTimestamp(25, null);
      } else {
        statement.setTimestamp(25, Timestamp.valueOf(normalizedAuctionStartedAt));
      }
      if (normalizedAuctionPublicEndAt == null) {
        statement.setTimestamp(26, null);
      } else {
        statement.setTimestamp(26, Timestamp.valueOf(normalizedAuctionPublicEndAt));
      }
      if (normalizedAuctionEndAt == null) {
        statement.setTimestamp(27, null);
      } else {
        statement.setTimestamp(27, Timestamp.valueOf(normalizedAuctionEndAt));
      }
      statement.setObject(28, normalizedAuctionHighestBid);
      statement.setObject(29, normalizedAuctionHighestBidderUserId);
      statement.setObject(30, normalizedAuctionHighestBidderUuid == null ? null : normalizedAuctionHighestBidderUuid.toString());
      statement.setObject(31, normalizedAuctionHighestBidId);
      if (normalizedAuctionLastBidAt == null) {
        statement.setTimestamp(32, null);
      } else {
        statement.setTimestamp(32, Timestamp.valueOf(normalizedAuctionLastBidAt));
      }
      statement.setLong(33, listingId);
      statement.executeUpdate();
    }
    MarketListing refreshed = readListingForUpdate(connection, listingId);
    return new ListingSettingsUpdateResult(
        refreshed.id(),
        refreshed.currency(),
        refreshed.price(),
        refreshed.marketSide(),
        refreshed.tagCode(),
        refreshed.tagVersion(),
        refreshed.remark(),
        refreshed.displayNameOverride(),
        refreshed.displayMaterial(),
        refreshed.displayIconPath(),
        refreshed.sourceMode(),
        refreshed.supplyBatchSize(),
        refreshed.supplyMaxStock(),
        refreshed.quantityTotal(),
        refreshed.tradeMode(),
        refreshed.dynamicPricingEnabled(),
        refreshed.dynamicAlgorithm(),
        refreshed.dynamicParamsJson(),
        refreshed.dynamicBasePrice(),
        refreshed.dynamicFloorPrice(),
        refreshed.dynamicCapPrice(),
        refreshed.dynamicPriceStep(),
        refreshed.dynamicDemandScore(),
        refreshed.auctionAlgorithm(),
        refreshed.auctionParamsJson(),
        refreshed.auctionStartPrice(),
        refreshed.auctionMinIncrement(),
        refreshed.auctionStartedAt(),
        refreshed.auctionPublicEndAt(),
        refreshed.auctionEndAt(),
        refreshed.auctionHighestBid(),
        refreshed.auctionHighestBidderUserId(),
        refreshed.auctionHighestBidId(),
        refreshed.auctionLastBidAt());
  }

  private TradeMode resolveTradeMode(String raw, TradeMode defaultValue) {
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    try {
      return TradeMode.valueOf(normalized);
    } catch (IllegalArgumentException exception) {
      throw new ServiceException("invalid_trade_mode", "Trade mode must be DIRECT or AUCTION");
    }
  }

  private Long normalizeOptionalPositive(Long value, String errorCode) {
    if (value == null) {
      return null;
    }
    if (value <= 0L) {
      throw new ServiceException(errorCode, "Value must be positive");
    }
    return value;
  }

  private long safeAddPositive(long base, long delta) {
    if (base > Long.MAX_VALUE - Math.max(0L, delta)) {
      throw new ServiceException("invalid_bid", "Bid amount exceeds numeric limit");
    }
    return base + Math.max(0L, delta);
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
      notifySupplyPausedIfOnline(listing);
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
        notifySupplyPausedIfOnline(listing);
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

  private void notifySupplyPausedIfOnline(MarketListing listing) {
    Player player = Bukkit.getPlayer(listing.sellerUuid());
    if (player != null && player.isOnline()) {
      player.sendMessage(messageService.format(
          player,
          "chat.market.supply_paused_external",
          java.util.Map.of("listingId", listing.id())));
    }
  }

  private void notifyPlayerAsync(UUID playerUuid, String message) {
    if (playerUuid == null || message == null || message.isBlank()) {
      return;
    }
    Bukkit.getScheduler().runTask(plugin, () -> {
      Player player = Bukkit.getPlayer(playerUuid);
      if (player != null && player.isOnline()) {
        player.sendMessage(message);
      }
    });
  }

  private void publishListingCreatedEvent(
      long sellerUserId,
      String sellerName,
      ListingCreateResult result,
      TradeMode tradeMode) {
    try {
      String itemLabel = formatMaterial(result.material());
      String amountText = formatAmount(result.price(), result.currency());
      broadcastService.broadcastTemplate(
          "listing-created",
          Map.of(
              "listingId", result.listingId(),
              "seller", sellerName,
              "item", itemLabel,
              "quantity", result.quantity(),
              "price", result.price(),
              "currency", result.currency().name(),
              "priceText", amountText,
              "tradeMode", tradeMode.name()));
      notifyMarketEvent(
          sellerUserId,
          "MARKET_LISTED",
          "上架提醒",
          TEMPLATE_MARKET_LISTED,
          "你的上架 #" + result.listingId() + " 已发布："
              + itemLabel + " x" + result.quantity() + "，单价 " + amountText + "。",
          Map.of(
              "listingId", result.listingId(),
              "item", itemLabel,
              "quantity", result.quantity(),
              "priceText", amountText));
    } catch (Exception exception) {
      plugin.getLogger().warning("Failed to publish listing-created event: " + exception.getMessage());
    }
  }

  private void publishTradeCreatedEvent(long tradeId) {
    try {
      TradeNoticeContext context = readTradeNoticeContext(tradeId);
      if (context == null) {
        return;
      }
      String totalText = formatAmount(context.totalPrice(), context.currency());
      String itemLabel = formatMaterial(context.itemMaterial());
      broadcastService.broadcastTemplate(
          "trade-success",
          Map.of(
              "tradeId", context.tradeId(),
              "listingId", context.listingId(),
              "seller", context.sellerName(),
              "buyer", context.buyerName(),
              "item", itemLabel,
              "quantity", context.quantity(),
              "total", context.totalPrice(),
              "currency", context.currency().name(),
              "totalText", totalText));
      notifyMarketEvents(
          List.of(context.sellerUserId(), context.buyerUserId()),
          "MARKET_TRADE",
          "市场成交",
          TEMPLATE_MARKET_TRADE,
          "上架 #" + context.listingId() + " 已成交："
              + itemLabel + " x" + context.quantity() + "，总价 " + totalText + "。",
          Map.of(
              "listingId", context.listingId(),
              "item", itemLabel,
              "quantity", context.quantity(),
              "totalText", totalText));
    } catch (Exception exception) {
      plugin.getLogger().warning("Failed to publish trade event: " + exception.getMessage());
    }
  }

  private void publishBidCreatedEvent(BidResult result) {
    try {
      BidNoticeContext context = readBidNoticeContext(result.bidId());
      if (context == null) {
        return;
      }
      if (result.sealedBid()) {
        broadcastService.broadcastTemplate(
            "auction-sealed-bid",
            Map.of(
                "listingId", context.listingId(),
                "bidder", context.bidderName(),
                "seller", context.sellerName(),
                "currency", context.currency().name()));
      } else {
        String amountText = formatAmount(context.bidAmount(), context.currency());
        broadcastService.broadcastTemplate(
            "auction-bid",
            Map.of(
                "listingId", context.listingId(),
                "bidder", context.bidderName(),
                "seller", context.sellerName(),
                "bidAmount", context.bidAmount(),
                "currency", context.currency().name(),
                "bidAmountText", amountText));
      }

      String bidderMessage = result.sealedBid()
          ? "你已提交拍卖 #" + context.listingId() + " 的密封出价。"
          : "你在拍卖 #" + context.listingId() + " 出价成功："
              + formatAmount(context.bidAmount(), context.currency()) + "。";
      notifyMarketEvent(
          context.bidderUserId(),
          "AUCTION_BID",
          "竞拍提醒",
          TEMPLATE_AUCTION_BID_SELF,
          bidderMessage,
          Map.of(
              "listingId", context.listingId(),
              "bidAmountText", formatAmount(context.bidAmount(), context.currency())));
      notifyMarketEvent(
          context.sellerUserId(),
          "AUCTION_BID",
          "竞拍提醒",
          TEMPLATE_AUCTION_BID_SELLER,
          "拍卖 #" + context.listingId() + " 收到来自 " + context.bidderName() + " 的新出价。",
          Map.of(
              "listingId", context.listingId(),
              "bidderName", context.bidderName()));
      if (result.previousHighestBidderUserId() != null
          && result.previousHighestBidderUserId() > 0L
          && result.previousHighestBidderUserId() != context.bidderUserId()) {
        notifyMarketEvent(
            result.previousHighestBidderUserId(),
            "AUCTION_OUTBID",
            "超价提醒",
            TEMPLATE_AUCTION_OUTBID,
            "你在拍卖 #" + context.listingId() + " 的领先出价已被超过。",
            Map.of("listingId", context.listingId()));
      }
    } catch (Exception exception) {
      plugin.getLogger().warning("Failed to publish auction bid event: " + exception.getMessage());
    }
  }

  private void persistAuctionNotices(List<AuctionSettlementNotice> notices) {
    if (notices == null || notices.isEmpty()) {
      return;
    }
    for (AuctionSettlementNotice notice : notices) {
      if (notice == null || notice.playerUuid() == null || notice.message() == null || notice.message().isBlank()) {
        continue;
      }
      try {
        Long userId = notificationService.findUserIdByBoundUuid(notice.playerUuid());
        if (userId == null || userId <= 0L) {
          continue;
        }
        notifyMarketEvent(
            userId,
            "AUCTION_SETTLEMENT",
            "拍卖结算",
            TEMPLATE_AUCTION_SETTLEMENT,
            notice.message(),
            Map.of("message", notice.message()));
      } catch (Exception exception) {
        plugin.getLogger().warning("Failed to persist auction notice: " + exception.getMessage());
      }
    }
  }

  private void notifyMarketEvent(
      long userId,
      String type,
      String title,
      String templateKey,
      String fallbackContent,
      Map<String, Object> placeholders) {
    if (userId <= 0L) {
      return;
    }
    RuntimeConfigService.NotificationSettings settings = runtimeConfigService.readNotificationSettings();
    if (!settings.marketEventsEnabled()) {
      return;
    }
    String content = renderNotificationTemplate(
        settings.template(templateKey),
        fallbackContent,
        placeholders);
    notificationService.createNotification(userId, type, title, content);
  }

  private void notifyMarketEvents(
      List<Long> userIds,
      String type,
      String title,
      String templateKey,
      String fallbackContent,
      Map<String, Object> placeholders) {
    if (userIds == null || userIds.isEmpty()) {
      return;
    }
    RuntimeConfigService.NotificationSettings settings = runtimeConfigService.readNotificationSettings();
    if (!settings.marketEventsEnabled()) {
      return;
    }
    String content = renderNotificationTemplate(
        settings.template(templateKey),
        fallbackContent,
        placeholders);
    notificationService.createNotifications(userIds, type, title, content);
  }

  private String renderNotificationTemplate(
      String template,
      String fallback,
      Map<String, Object> placeholders) {
    String result = template == null || template.isBlank() ? fallback : template;
    if (result == null || result.isBlank()) {
      return "";
    }
    if (placeholders == null || placeholders.isEmpty()) {
      return result;
    }
    for (Map.Entry<String, Object> entry : placeholders.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.isBlank()) {
        continue;
      }
      String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
      result = result.replace("{" + key + "}", value);
    }
    return result;
  }

  private TradeNoticeContext readTradeNoticeContext(long tradeId) {
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT mt.id AS trade_id, mt.listing_id, mt.seller_user_id, seller.username AS seller_name,
                 mt.buyer_user_id, buyer.username AS buyer_name, mt.currency, mt.quantity, mt.total_price,
                 ml.item_material
          FROM market_trades mt
          JOIN market_listings ml ON ml.id = mt.listing_id
          JOIN web_users seller ON seller.id = mt.seller_user_id
          JOIN web_users buyer ON buyer.id = mt.buyer_user_id
          WHERE mt.id = ?
          LIMIT 1
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, tradeId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          return new TradeNoticeContext(
              resultSet.getLong("trade_id"),
              resultSet.getLong("listing_id"),
              resultSet.getLong("seller_user_id"),
              resultSet.getString("seller_name"),
              resultSet.getLong("buyer_user_id"),
              resultSet.getString("buyer_name"),
              CurrencyType.valueOf(resultSet.getString("currency")),
              resultSet.getInt("quantity"),
              resultSet.getLong("total_price"),
              resultSet.getString("item_material"));
        }
      }
    });
  }

  private BidNoticeContext readBidNoticeContext(long bidId) {
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT mb.id AS bid_id, mb.listing_id, mb.bid_amount, mb.bidder_user_id, bidder.username AS bidder_name,
                 ml.seller_user_id, seller.username AS seller_name, ml.currency
          FROM market_bids mb
          JOIN market_listings ml ON ml.id = mb.listing_id
          JOIN web_users bidder ON bidder.id = mb.bidder_user_id
          JOIN web_users seller ON seller.id = ml.seller_user_id
          WHERE mb.id = ?
          LIMIT 1
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, bidId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          return new BidNoticeContext(
              resultSet.getLong("bid_id"),
              resultSet.getLong("listing_id"),
              resultSet.getLong("bidder_user_id"),
              resultSet.getString("bidder_name"),
              resultSet.getLong("seller_user_id"),
              resultSet.getString("seller_name"),
              CurrencyType.valueOf(resultSet.getString("currency")),
              resultSet.getLong("bid_amount"));
        }
      }
    });
  }

  private String formatAmount(long amount, CurrencyType currency) {
    return amount + " " + currency.name();
  }

  private String formatMaterial(String material) {
    if (material == null || material.isBlank()) {
      return "UNKNOWN";
    }
    String normalized = material.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
    String[] parts = normalized.split(" ");
    StringBuilder builder = new StringBuilder();
    for (String part : parts) {
      if (part.isBlank()) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(' ');
      }
      builder.append(Character.toUpperCase(part.charAt(0)));
      if (part.length() > 1) {
        builder.append(part.substring(1));
      }
    }
    return builder.length() == 0 ? material : builder.toString();
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

  private void consumeBuyFulfillItems(UUID sellerUuid, byte[] templateBlob, int quantity) {
    if (quantity <= 0) {
      throw new ServiceException("invalid_quantity", "Quantity must be positive");
    }
    runSync(() -> {
      Player player = sellerUuid == null ? null : Bukkit.getPlayer(sellerUuid);
      if (player == null || !player.isOnline()) {
        throw new ServiceException("fulfill_item_not_match", "Seller must be online with matching items");
      }
      ItemStack template = decodeBuyFulfillTemplate(templateBlob);
      int available = 0;
      for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (!isSameSupplyItem(stack, template)) {
          continue;
        }
        available += stack.getAmount();
        if (available >= quantity) {
          break;
        }
      }
      if (available < quantity) {
        throw new ServiceException("fulfill_item_not_match", "Seller inventory does not match BUY template");
      }
      int remaining = quantity;
      for (int slot = 0; slot < player.getInventory().getSize() && remaining > 0; slot++) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (!isSameSupplyItem(stack, template)) {
          continue;
        }
        int consumed = Math.min(remaining, stack.getAmount());
        int left = stack.getAmount() - consumed;
        if (left <= 0) {
          player.getInventory().setItem(slot, null);
        } else {
          ItemStack updated = stack.clone();
          updated.setAmount(left);
          player.getInventory().setItem(slot, updated);
        }
        remaining -= consumed;
      }
      if (remaining > 0) {
        throw new ServiceException("fulfill_item_not_match", "Seller inventory changed during fulfill");
      }
      return null;
    });
  }

  private void restoreBuyFulfillItemsSafely(UUID sellerUuid, byte[] templateBlob, int quantity) {
    if (quantity <= 0) {
      return;
    }
    try {
      runSync(() -> {
        Player player = sellerUuid == null ? null : Bukkit.getPlayer(sellerUuid);
        if (player == null || !player.isOnline()) {
          return null;
        }
        ItemStack template = decodeBuyFulfillTemplate(templateBlob);
        int remaining = quantity;
        while (remaining > 0) {
          ItemStack chunk = template.clone();
          chunk.setAmount(Math.min(chunk.getMaxStackSize(), remaining));
          remaining -= chunk.getAmount();
          player.getInventory().addItem(chunk)
              .values()
              .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
        return null;
      });
    } catch (Exception exception) {
      plugin.getLogger().warning(
          "Failed to restore fulfilled BUY items for player "
              + sellerUuid
              + ": "
              + exception.getMessage());
    }
  }

  private ItemStack decodeBuyFulfillTemplate(byte[] templateBlob) {
    try {
      ItemStack template = itemSnapshotCodec.deserialize(templateBlob);
      if (template == null || template.getType() == Material.AIR) {
        throw new ServiceException("fulfill_item_not_match", "BUY template item is invalid");
      }
      template = template.clone();
      template.setAmount(1);
      return template;
    } catch (ServiceException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ServiceException("fulfill_item_not_match", "BUY template item cannot be parsed");
    }
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
      throw new ServiceException("sync_timeout", "Sync task timed out; please try again later");
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
    if (listing.tradeMode() == TradeMode.DIRECT && listing.dynamicPricingEnabled()) {
      MarketAlgorithmRegistry.DynamicAlgorithmType algorithmType = MarketAlgorithmRegistry.DynamicAlgorithmType
        .fromRaw(listing.dynamicAlgorithm());
      JsonObject dynamicParams = MarketAlgorithmRegistry.parseParams(listing.dynamicParamsJson());
      long basePrice = listing.dynamicBasePrice() == null ? listing.price() : listing.dynamicBasePrice();
      long step = listing.dynamicPriceStep() == null ? 1L : Math.max(1L, listing.dynamicPriceStep());
      long nextDemandScore = MarketAlgorithmRegistry.computeDemandAfterPurchase(
        algorithmType,
        listing.dynamicDemandScore(),
        buyQuantity,
        dynamicParams);
      long nextPrice = MarketAlgorithmRegistry.computeDynamicPrice(
        algorithmType,
        Math.max(1L, basePrice),
        nextDemandScore,
        step,
        listing.dynamicFloorPrice(),
        listing.dynamicCapPrice(),
        dynamicParams);
      String dynamicSql = """
          UPDATE market_listings
          SET dynamic_demand_score = ?, price = ?
          WHERE id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(dynamicSql)) {
        statement.setLong(1, nextDemandScore);
        statement.setLong(2, nextPrice);
        statement.setLong(3, listing.id());
        statement.executeUpdate();
      }
    }
    return readListingForUpdate(connection, listing.id());
  }

  private UnlistResult adminUnlistInTransaction(Connection connection, long listingId)
      throws SQLException {
    MarketListing listing = readListingForUpdate(connection, listingId);
    if (!"ACTIVE".equalsIgnoreCase(listing.status()) && !"PAUSED".equalsIgnoreCase(listing.status())) {
      throw new ServiceException("listing_unavailable", "Supply mode is not enabled for this listing");
    }
    if (listing.isAuction()) {
      refundAuctionBidsIfPresent(connection, listing, "admin-unlist");
      listing = readListingForUpdate(connection, listing.id());
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
    listing = readListingForUpdate(connection, listing.id());
    if (listing.marketSide() == MarketSide.BUY) {
      refundBuyEscrowIfNeeded(connection, listing, "admin-unlist");
      return new UnlistResult(listing.id(), listing.currency(), listing.price(), listing.quantity());
    }
    if (listing.quantity() > 0) {
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
    }
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
    String targetServerId = playerPresenceService.resolveOnlineServer(connection, targetUuid);
    if ((targetServerId == null || targetServerId.isBlank())) {
      Player player = Bukkit.getPlayer(targetUuid);
      if (player != null && player.isOnline()) {
        String localServerId = settingsSupplier.get().clusterSettings().serverId();
        targetServerId = localServerId == null || localServerId.isBlank() ? null : localServerId;
      }
    }
    String sql = """
        INSERT INTO market_item_deliveries (
          listing_id, trade_id, target_user_id, target_uuid, target_server_id,
          item_blob, quantity, delivery_type, status, next_retry_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
      statement.setString(5, targetServerId);
      statement.setBytes(6, itemBlob);
      statement.setInt(7, quantity);
      statement.setString(8, deliveryType.name());
      statement.setString(9, status);
      statement.setTimestamp(10, Timestamp.valueOf(nextRetryAt));
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
               market_side, tag_code, tag_version, escrow_total, escrow_remaining,
               item_material, display_name_override, display_material, display_icon_path, raw_item_blob,
               item_meta_json, remark, item_hash, status, source_mode,
               supply_world, supply_x, supply_y, supply_z, supply_batch_size, supply_max_stock,
         supply_loaded_total, supply_sold_total, supply_last_loaded_amount, supply_last_loaded_at,
           trade_mode, dynamic_pricing_enabled, dynamic_algorithm, dynamic_params_json,
           dynamic_base_price, dynamic_floor_price, dynamic_cap_price, dynamic_price_step,
           dynamic_demand_score,
           auction_algorithm, auction_params_json,
           auction_start_price, auction_min_increment, auction_started_at,
           auction_public_end_at, auction_end_at,
         auction_highest_bid, auction_highest_bidder_user_id, auction_highest_bidder_uuid,
         auction_highest_bid_id, auction_last_bid_at
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
            MarketSide.fromRaw(resultSet.getString("market_side")),
            normalizeTagCode(resultSet.getString("tag_code")),
            resultSet.getInt("tag_version"),
            resultSet.getLong("escrow_total"),
            resultSet.getLong("escrow_remaining"),
            resultSet.getString("item_material"),
            resultSet.getString("display_name_override"),
            resultSet.getString("display_material"),
            resultSet.getString("display_icon_path"),
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
              : resultSet.getTimestamp("supply_last_loaded_at").toLocalDateTime(),
            TradeMode.fromRaw(resultSet.getString("trade_mode")),
            resultSet.getBoolean("dynamic_pricing_enabled"),
            resultSet.getString("dynamic_algorithm"),
            resultSet.getString("dynamic_params_json"),
            (Long) resultSet.getObject("dynamic_base_price"),
            (Long) resultSet.getObject("dynamic_floor_price"),
            (Long) resultSet.getObject("dynamic_cap_price"),
            (Long) resultSet.getObject("dynamic_price_step"),
            resultSet.getLong("dynamic_demand_score"),
            resultSet.getString("auction_algorithm"),
            resultSet.getString("auction_params_json"),
            (Long) resultSet.getObject("auction_start_price"),
            (Long) resultSet.getObject("auction_min_increment"),
            resultSet.getTimestamp("auction_started_at") == null
              ? null
              : resultSet.getTimestamp("auction_started_at").toLocalDateTime(),
            resultSet.getTimestamp("auction_public_end_at") == null
              ? null
              : resultSet.getTimestamp("auction_public_end_at").toLocalDateTime(),
            resultSet.getTimestamp("auction_end_at") == null
              ? null
              : resultSet.getTimestamp("auction_end_at").toLocalDateTime(),
            (Long) resultSet.getObject("auction_highest_bid"),
            (Long) resultSet.getObject("auction_highest_bidder_user_id"),
            resultSet.getString("auction_highest_bidder_uuid") == null
              ? null
              : UUID.fromString(resultSet.getString("auction_highest_bidder_uuid")),
            (Long) resultSet.getObject("auction_highest_bid_id"),
            resultSet.getTimestamp("auction_last_bid_at") == null
              ? null
              : resultSet.getTimestamp("auction_last_bid_at").toLocalDateTime());
      }
    }
  }

  private List<ListingView> readListingViews(ResultSet resultSet) throws SQLException {
    List<ListingView> listings = new ArrayList<>();
    while (resultSet.next()) {
      String status = resultSet.getString("status");
      TradeMode tradeMode = TradeMode.fromRaw(resultSet.getString("trade_mode"));
      String auctionAlgorithm = resultSet.getString("auction_algorithm");
      String auctionParamsJson = resultSet.getString("auction_params_json");
      Long auctionStartPrice = (Long) resultSet.getObject("auction_start_price");
      LocalDateTime auctionStartedAt = resultSet.getTimestamp("auction_started_at") == null
          ? null
          : resultSet.getTimestamp("auction_started_at").toLocalDateTime();
      LocalDateTime auctionPublicEndAt = resultSet.getTimestamp("auction_public_end_at") == null
          ? null
          : resultSet.getTimestamp("auction_public_end_at").toLocalDateTime();
      LocalDateTime auctionEndAt = resultSet.getTimestamp("auction_end_at") == null
          ? null
          : resultSet.getTimestamp("auction_end_at").toLocalDateTime();

      long displayPrice = resultSet.getLong("price");
      if (tradeMode == TradeMode.AUCTION
          && "ACTIVE".equalsIgnoreCase(status)
          && MarketAlgorithmRegistry.AuctionAlgorithmType.fromRaw(auctionAlgorithm)
              == MarketAlgorithmRegistry.AuctionAlgorithmType.DUTCH_AUCTION_V1) {
        JsonObject auctionParams = MarketAlgorithmRegistry.parseParams(auctionParamsJson);
        long startPrice = auctionStartPrice == null ? Math.max(1L, displayPrice) : Math.max(1L, auctionStartPrice);
        long floorPrice = Math.max(1L, MarketAlgorithmRegistry.getLongParam(auctionParams, "floorPrice", 1L));
        displayPrice = MarketAlgorithmRegistry.computeDutchPrice(
            startPrice,
            floorPrice,
            auctionStartedAt,
            auctionEndAt,
            LocalDateTime.now());
      }

      listings.add(new ListingView(
          resultSet.getLong("id"),
          resultSet.getLong("seller_user_id"),
          resultSet.getString("seller_name"),
          UUID.fromString(resultSet.getString("seller_uuid")),
          CurrencyType.valueOf(resultSet.getString("currency")),
          displayPrice,
          resultSet.getInt("quantity"),
          resultSet.getInt("quantity_total"),
          MarketSide.fromRaw(resultSet.getString("market_side")),
          normalizeTagCode(resultSet.getString("tag_code")),
          resultSet.getInt("tag_version"),
          resultSet.getLong("escrow_total"),
          resultSet.getLong("escrow_remaining"),
          resultSet.getString("item_material"),
          resultSet.getString("display_name_override"),
          resultSet.getString("display_material"),
          resultSet.getString("display_icon_path"),
          resultSet.getString("item_meta_json"),
          resultSet.getString("remark"),
          status,
          resultSet.getTimestamp("created_at").toLocalDateTime(),
          SupplyMode.fromRaw(resultSet.getString("source_mode")),
          (Integer) resultSet.getObject("supply_batch_size"),
          (Integer) resultSet.getObject("supply_max_stock"),
          resultSet.getLong("supply_loaded_total"),
          resultSet.getLong("supply_sold_total"),
          (Integer) resultSet.getObject("supply_last_loaded_amount"),
          resultSet.getTimestamp("supply_last_loaded_at") == null
              ? null
              : resultSet.getTimestamp("supply_last_loaded_at").toLocalDateTime(),
            tradeMode,
            resultSet.getBoolean("dynamic_pricing_enabled"),
            resultSet.getString("dynamic_algorithm"),
            resultSet.getString("dynamic_params_json"),
            (Long) resultSet.getObject("dynamic_base_price"),
            (Long) resultSet.getObject("dynamic_floor_price"),
            (Long) resultSet.getObject("dynamic_cap_price"),
            (Long) resultSet.getObject("dynamic_price_step"),
            resultSet.getLong("dynamic_demand_score"),
            auctionAlgorithm,
            auctionParamsJson,
            auctionStartPrice,
            (Long) resultSet.getObject("auction_min_increment"),
            auctionStartedAt,
            auctionPublicEndAt,
            auctionEndAt,
            (Long) resultSet.getObject("auction_highest_bid"),
            (Long) resultSet.getObject("auction_highest_bidder_user_id"),
            resultSet.getString("auction_highest_bidder_uuid") == null
              ? null
              : UUID.fromString(resultSet.getString("auction_highest_bidder_uuid")),
            (Long) resultSet.getObject("auction_highest_bid_id"),
            resultSet.getTimestamp("auction_last_bid_at") == null
              ? null
              : resultSet.getTimestamp("auction_last_bid_at").toLocalDateTime()));
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
          MarketSide.fromRaw(resultSet.getString("market_side")),
          normalizeTagCode(resultSet.getString("tag_code")),
          resultSet.getInt("tag_version"),
          resultSet.getLong("escrow_total"),
          resultSet.getLong("escrow_remaining"),
          resultSet.getString("item_material"),
          resultSet.getString("display_name_override"),
          resultSet.getString("display_material"),
          resultSet.getString("display_icon_path"),
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

  private String normalizeDisplayNameOverride(String raw) {
    if (raw == null) {
      return null;
    }
    String normalized = raw.trim();
    if (normalized.isEmpty()) {
      return null;
    }
    if (normalized.length() > 128) {
      return normalized.substring(0, 128);
    }
    return normalized;
  }

  private String normalizeDisplayMaterial(String raw) {
    if (raw == null) {
      return null;
    }
    String normalized = raw.trim()
        .toUpperCase(Locale.ROOT)
        .replace("MINECRAFT:", "")
        .replaceAll("[^A-Z0-9]+", "_")
        .replaceAll("^_+|_+$", "");
    if (normalized.isEmpty()) {
      return null;
    }
    if (normalized.length() > 64) {
      return normalized.substring(0, 64);
    }
    return normalized;
  }

  private String normalizeDisplayIconPath(String raw) {
    if (raw == null) {
      return null;
    }
    String normalized = raw.trim();
    if (normalized.isEmpty()) {
      return null;
    }
    if (normalized.length() > 255) {
      return normalized.substring(0, 255);
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

  private int resolveListingLimit(BoundUser seller) {
    return userMarketSettingsService.resolveListingLimit(
        seller.userId(),
        seller.boundUuid(),
        settingsSupplier.get().marketMaxActiveListings())
        .effectiveLimit();
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

  private record TradeNoticeContext(
      long tradeId,
      long listingId,
      long sellerUserId,
      String sellerName,
      long buyerUserId,
      String buyerName,
      CurrencyType currency,
      int quantity,
      long totalPrice,
      String itemMaterial) {
  }

  private record BidNoticeContext(
      long bidId,
      long listingId,
      long bidderUserId,
      String bidderName,
      long sellerUserId,
      String sellerName,
      CurrencyType currency,
      long bidAmount) {
  }

  private record ExistingBid(
      long bidId,
      long listingId,
      long bidAmount,
      String status) {
  }

  private record AuctionBidRefund(
      long bidId,
      long bidderUserId,
      UUID bidderUuid,
      long bidAmount) {
  }

  private record AuctionSettlementNotice(UUID playerUuid, String message) {
  }

  private record DynamicDecayTarget(
      long listingId,
      long currentPrice,
      String dynamicAlgorithm,
      String dynamicParamsJson,
      Long dynamicBasePrice,
      Long dynamicFloorPrice,
      Long dynamicCapPrice,
      Long dynamicPriceStep,
      long dynamicDemandScore) {
  }

  private record MarketListing(
      long id,
      long sellerUserId,
      UUID sellerUuid,
      CurrencyType currency,
      long price,
      int quantity,
      int quantityTotal,
      MarketSide marketSide,
      String tagCode,
      int tagVersion,
      long escrowTotal,
      long escrowRemaining,
      String itemMaterial,
      String displayNameOverride,
      String displayMaterial,
      String displayIconPath,
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
      LocalDateTime supplyLastLoadedAt,
      TradeMode tradeMode,
      boolean dynamicPricingEnabled,
      String dynamicAlgorithm,
      String dynamicParamsJson,
      Long dynamicBasePrice,
      Long dynamicFloorPrice,
      Long dynamicCapPrice,
      Long dynamicPriceStep,
      long dynamicDemandScore,
      String auctionAlgorithm,
      String auctionParamsJson,
      Long auctionStartPrice,
      Long auctionMinIncrement,
      LocalDateTime auctionStartedAt,
      LocalDateTime auctionPublicEndAt,
      LocalDateTime auctionEndAt,
      Long auctionHighestBid,
      Long auctionHighestBidderUserId,
      UUID auctionHighestBidderUuid,
      Long auctionHighestBidId,
      LocalDateTime auctionLastBidAt) {

    boolean isSupply() {
      return sourceMode == SupplyMode.SUPPLY;
    }

    boolean isAuction() {
      return tradeMode == TradeMode.AUCTION;
    }

    MarketAlgorithmRegistry.AuctionAlgorithmType auctionAlgorithmType() {
      return MarketAlgorithmRegistry.AuctionAlgorithmType.fromRaw(auctionAlgorithm);
    }

    boolean isDutchAuction() {
      return auctionAlgorithmType() == MarketAlgorithmRegistry.AuctionAlgorithmType.DUTCH_AUCTION_V1;
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

  enum TradeMode {
    DIRECT,
    AUCTION;

    static TradeMode fromRaw(String raw) {
      if (raw == null || raw.isBlank()) {
        return DIRECT;
      }
      try {
        return TradeMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException exception) {
        return DIRECT;
      }
    }
  }

  enum TradeState {
    CREATED,
    EXISTING
  }

  enum BidState {
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
      long price,
      MarketSide side,
      String tag,
      long escrowTotal,
      long escrowRemaining) {
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
      MarketSide side,
      String tag,
      List<String> tags,
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
      MarketSide side,
      String tag,
      int tagVersion,
      long escrowTotal,
      long escrowRemaining,
      String itemMaterial,
      String displayNameOverride,
      String displayMaterial,
      String displayIconPath,
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
      LocalDateTime supplyLastLoadedAt,
      TradeMode tradeMode,
      boolean dynamicPricingEnabled,
      String dynamicAlgorithm,
      String dynamicParamsJson,
      Long dynamicBasePrice,
      Long dynamicFloorPrice,
      Long dynamicCapPrice,
      Long dynamicPriceStep,
      long dynamicDemandScore,
      String auctionAlgorithm,
      String auctionParamsJson,
      Long auctionStartPrice,
      Long auctionMinIncrement,
      LocalDateTime auctionStartedAt,
      LocalDateTime auctionPublicEndAt,
      LocalDateTime auctionEndAt,
      Long auctionHighestBid,
      Long auctionHighestBidderUserId,
      UUID auctionHighestBidderUuid,
      Long auctionHighestBidId,
      LocalDateTime auctionLastBidAt) {
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
      MarketSide side,
      String tag,
      int tagVersion,
      long escrowTotal,
      long escrowRemaining,
      String itemMaterial,
      String displayNameOverride,
      String displayMaterial,
      String displayIconPath,
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

  record TagRecalcResult(int scanned, int changed, long elapsedMs) {
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
      MarketSide side,
      String tag,
      int tagVersion,
      String remark,
      String displayNameOverride,
      String displayMaterial,
      String displayIconPath,
      SupplyMode sourceMode,
      Integer supplyBatchSize,
      Integer supplyMaxStock,
      int quantityTotal,
      TradeMode tradeMode,
      boolean dynamicPricingEnabled,
      String dynamicAlgorithm,
      String dynamicParamsJson,
      Long dynamicBasePrice,
      Long dynamicFloorPrice,
      Long dynamicCapPrice,
      Long dynamicPriceStep,
      long dynamicDemandScore,
      String auctionAlgorithm,
      String auctionParamsJson,
      Long auctionStartPrice,
      Long auctionMinIncrement,
      LocalDateTime auctionStartedAt,
      LocalDateTime auctionPublicEndAt,
      LocalDateTime auctionEndAt,
      Long auctionHighestBid,
      Long auctionHighestBidderUserId,
      Long auctionHighestBidId,
      LocalDateTime auctionLastBidAt) {
    }

  record ListingVisualUpdateResult(
      long listingId,
      String previousDisplayIconPath,
      String displayNameOverride,
      String displayMaterial,
      String displayIconPath) {
  }

  record BidResult(
      BidState state,
      long bidId,
      long listingId,
      CurrencyType currency,
      long bidAmount,
      long currentHighestBid,
      Long previousHighestBid,
      Long previousHighestBidderUserId,
      LocalDateTime auctionEndAt,
      String auctionAlgorithm,
      boolean sealedBid,
      Long minimumRequiredBid) {
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





