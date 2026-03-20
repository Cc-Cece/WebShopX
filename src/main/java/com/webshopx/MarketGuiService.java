package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

class MarketGuiService {
  private static final int MAIN_SIZE = 27;
  private static final int INPUT_SIZE = 54;
  private static final int MANAGE_SIZE = 54;
  private static final int DETAIL_SIZE = 45;
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

  private final MarketService marketService;
  private final Supplier<PluginSettings> settingsSupplier;
  private final MessageService messageService;
  private final Map<UUID, CreationSession> creationSessions = new HashMap<>();
  private final Map<UUID, ChatSession> chatSessions = new HashMap<>();
  private final Map<UUID, SupplyBindSession> supplyBindSessions = new HashMap<>();

  MarketGuiService(
      MarketService marketService,
      Supplier<PluginSettings> settingsSupplier,
      MessageService messageService) {
    this.marketService = marketService;
    this.settingsSupplier = settingsSupplier;
    this.messageService = messageService;
  }

  String humanizeError(Player player, ServiceException exception) {
    return switch (exception.code()) {
      case "invalid_price" -> messageService.get(player, "error.market.invalid_price");
      case "invalid_amount" -> messageService.get(player, "error.market.invalid_amount");
      case "empty_hand" -> messageService.get(player, "error.market.empty_hand");
      case "invalid_item" -> humanizeInvalidItem(player, exception.getMessage());
      case "insufficient_item" -> messageService.get(player, "error.market.insufficient_item");
      case "not_bound" -> messageService.get(player, "error.market.not_bound");
      case "supply_empty" -> messageService.get(player, "error.market.supply_empty");
      case "supply_missing" -> messageService.get(player, "error.market.supply_missing");
      case "invalid_listing" -> messageService.get(player, "error.market.invalid_listing");
      case "listing_missing" -> messageService.get(player, "error.market.listing_missing");
      case "listing_unavailable" -> messageService.get(player, "error.market.listing_unavailable");
      case "invalid_trade" -> messageService.get(player, "error.market.invalid_trade");
      case "insufficient_quantity" -> messageService.get(player, "error.market.insufficient_quantity");
      case "forbidden" -> messageService.get(player, "error.market.forbidden");
      case "sync_timeout" -> messageService.get(player, "error.market.sync_timeout");
      case "sync_interrupted" -> messageService.get(player, "error.market.sync_interrupted");
      case "listing_empty" -> messageService.get(player, "error.market.listing_empty");
      case "listing_limit" -> messageService.get(player, "error.market.listing_limit");
      default -> exception.getMessage();
    };
  }

  private String humanizeInvalidItem(Player player, String rawMessage) {
    if (rawMessage == null) {
      return messageService.get(player, "error.market.invalid_item");
    }
    return switch (rawMessage) {
      case "请先在箱子 GUI 中放入要上架的物品" -> messageService.get(player, "error.market.input_empty");
      case "普通上架一次只允许放入一种物品" -> messageService.get(player, "error.market.manual_single_item");
      case "未检测到有效物品" -> messageService.get(player, "error.market.invalid_item");
      case "请先放入 1 个模板物品" -> messageService.get(player, "error.market.template_missing");
      case "供货箱上架只允许放入 1 个模板物品" -> messageService.get(player, "error.market.template_single");
      default -> messageService.get(player, "error.market.invalid_item");
    };
  }

  String marketStatusLabel(Player player, String status) {
    String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "ACTIVE" -> messageService.get(player, "enum.market_status.active");
      case "PAUSED" -> messageService.get(player, "enum.market_status.paused");
      case "SOLD" -> messageService.get(player, "enum.market_status.sold");
      case "UNLISTED" -> messageService.get(player, "enum.market_status.unlisted");
      case "SUPPLY_EMPTY" -> messageService.get(player, "enum.market_status.supply_empty");
      default -> normalized;
    };
  }

  private String msg(Player player, String key) {
    return messageService.get(player, key);
  }

  private String msg(Player player, String key, Map<String, ?> params) {
    return messageService.format(player, key, params);
  }

  private List<String> msgList(Player player, String key) {
    return messageService.getList(player, key);
  }

  private List<String> msgList(Player player, String key, Map<String, ?> params) {
    return messageService.getList(player, key, params);
  }

  void openMainMenu(Player player) {
    MainHolder holder = new MainHolder(player.getUniqueId());
    Inventory inventory = createInventory(holder, MAIN_SIZE, msg(player, "gui.market.title.main"));
    fill(inventory);
    inventory.setItem(
        11,
        buildButton(
            Material.CHEST,
            msg(player, "gui.market.main.manual.title"),
            msg(player, "gui.market.main.manual.subtitle"),
            msgList(player, "gui.market.main.manual.lore")));
    inventory.setItem(
        13,
        buildButton(
            Material.HOPPER,
            msg(player, "gui.market.main.supply.title"),
            msg(player, "gui.market.main.supply.subtitle"),
            msgList(player, "gui.market.main.supply.lore")));
    inventory.setItem(
        15,
        buildButton(
            Material.BOOK,
            msg(player, "gui.market.main.manage.title"),
            msg(player, "gui.market.main.manage.subtitle"),
            msgList(player, "gui.market.main.manage.lore")));
    player.openInventory(inventory);
  }

  void handleInventoryClick(Player player, InventoryClickEvent event) {
    if (!(event.getView().getTopInventory().getHolder() instanceof GuiHolder holder)) {
      return;
    }
    if (!holder.playerId().equals(player.getUniqueId())) {
      player.closeInventory();
      return;
    }
    switch (holder.kind()) {
      case MAIN -> handleMainClick(player, event.getRawSlot());
      case INPUT -> handleInputClick(player, (InputHolder) holder, event.getRawSlot());
      case MANAGE -> handleManageClick(player, event.getRawSlot());
      case DETAIL -> handleDetailClick(player, (DetailHolder) holder, event.getRawSlot());
    }
  }

  void handleInventoryClose(Player player, Inventory inventory) {
    CreationSession session = creationSessions.get(player.getUniqueId());
    if (session == null || session.inventory() != inventory || session.awaitingChat()) {
      return;
    }
    creationSessions.remove(player.getUniqueId());
    returnItems(player, inventory);
  }

  void handlePlayerChat(Player player, String message) {
    ChatSession session = chatSessions.get(player.getUniqueId());
    if (session == null) {
      return;
    }
    String trimmed = message == null ? "" : message.trim();
    if (trimmed.equalsIgnoreCase("cancel")) {
      cancelChatSession(player, msg(player, "chat.market.create_cancelled"));
      return;
    }
    if (trimmed.isBlank()) {
      player.sendMessage(msg(player, "chat.market.invalid_price_empty"));
      return;
    }
    long price;
    try {
      price = Long.parseLong(trimmed);
    } catch (NumberFormatException exception) {
      player.sendMessage(msg(player, "chat.market.invalid_price_integer"));
      return;
    }
    if (price <= 0) {
      player.sendMessage(msg(player, "chat.market.invalid_price_positive"));
      return;
    }

    chatSessions.remove(player.getUniqueId());
    CreationSession creation = creationSessions.remove(player.getUniqueId());
    if (creation == null) {
      player.sendMessage(msg(player, "chat.market.session_expired"));
      return;
    }

    try {
      if (session.mode() == ListingMode.MANUAL) {
        ItemStack listingItem = extractManualItem(creation.inventory());
        MarketService.ListingCreateResult result = marketService.createListingFromStack(
            player,
            listingItem,
            price,
            CurrencyType.GAME_COIN);
        player.sendMessage(msg(player, "chat.market.manual_success", Map.of(
            "listingId", result.listingId(),
            "material", result.material(),
            "quantity", result.quantity(),
            "price", result.price())));
      } else {
        ItemStack template = extractSupplyTemplate(creation.inventory());
        returnItems(player, creation.inventory());
        MarketService.ListingCreateResult result = marketService.createSupplyListingFromTemplate(
            player,
            session.source(),
            template,
            price,
            CurrencyType.GAME_COIN);
        player.sendMessage(msg(player, "chat.market.supply_success", Map.of(
            "listingId", result.listingId(),
            "material", result.material(),
            "quantity", result.quantity(),
            "price", result.price())));
      }
      openManageMenu(player);
    } catch (ServiceException exception) {
      player.sendMessage(msg(player, "chat.market.create_failed",
          Map.of("reason", humanizeError(player, exception))));
      returnItems(player, creation.inventory());
    }
  }

  boolean hasChatSession(UUID playerId) {
    return chatSessions.containsKey(playerId);
  }

  void beginSupplyBinding(Player player) {
    clearTransientState(player);
    supplyBindSessions.put(player.getUniqueId(), new SupplyBindSession(player.getUniqueId()));
    player.closeInventory();
    player.sendMessage(msg(player, "chat.market.bind_prompt"));
  }

  void handleSupplyBindClick(Player player, Block block) {
    SupplyBindSession bindSession = supplyBindSessions.remove(player.getUniqueId());
    if (bindSession == null) {
      return;
    }
    try {
      MarketService.SupplySourceDescriptor source = marketService.describeSupplySource(block);
      openInputInventory(player, ListingMode.SUPPLY, source, msg(player, "gui.market.title.supply_template"));
      player.sendMessage(msg(player, "chat.market.bind_success"));
    } catch (ServiceException exception) {
      player.sendMessage(msg(player, "chat.market.bind_failed",
          Map.of("reason", humanizeError(player, exception))));
    }
  }

  boolean isAwaitingSupplyBind(UUID playerId) {
    return supplyBindSessions.containsKey(playerId);
  }

  void openManageMenu(Player player) {
    List<MarketService.ListingView> listings;
    try {
      listings = marketService.listListingsForPlayer(player.getUniqueId(), 45);
    } catch (ServiceException exception) {
      player.sendMessage(msg(player, "chat.market.read_failed",
          Map.of("reason", humanizeError(player, exception))));
      openMainMenu(player);
      return;
    }
    ManageHolder holder = new ManageHolder(player.getUniqueId());
    Inventory inventory = createInventory(holder, MANAGE_SIZE, msg(player, "gui.market.title.manage"));
    fill(inventory);
    if (listings.isEmpty()) {
      inventory.setItem(
          22,
          buildButton(
              Material.BARRIER,
              msg(player, "gui.market.manage.empty.title"),
              msg(player, "gui.market.manage.empty.subtitle"),
              msgList(player, "gui.market.manage.empty.lore")));
    } else {
      for (int index = 0; index < Math.min(45, listings.size()); index++) {
        inventory.setItem(index, buildListingIcon(player, listings.get(index)));
      }
    }
    inventory.setItem(45, buildButton(Material.ARROW, msg(player, "gui.market.common.back.title"), msg(player, "gui.market.common.back.subtitle"), List.of()));
    inventory.setItem(49, buildButton(Material.SUNFLOWER, msg(player, "gui.market.common.refresh.title"), msg(player, "gui.market.common.refresh.subtitle"), List.of()));
    player.openInventory(inventory);
  }

  private void handleMainClick(Player player, int slot) {
    if (slot == 11) {
      openInputInventory(player, ListingMode.MANUAL, null, msg(player, "gui.market.title.manual"));
    } else if (slot == 13) {
      beginSupplyBinding(player);
    } else if (slot == 15) {
      openManageMenu(player);
    }
  }

  private void handleInputClick(Player player, InputHolder holder, int slot) {
    if (slot == 45) {
      CreationSession session = creationSessions.remove(player.getUniqueId());
      if (session != null) {
        returnItems(player, session.inventory());
      }
      openMainMenu(player);
      return;
    }
    if (slot != 49) {
      return;
    }
    CreationSession session = creationSessions.get(player.getUniqueId());
    if (session == null) {
      player.closeInventory();
      return;
    }
    try {
      if (session.mode() == ListingMode.MANUAL) {
        extractManualItem(session.inventory());
        player.sendMessage(msg(player, "chat.market.enter_price"));
      } else {
        extractSupplyTemplate(session.inventory());
        player.sendMessage(msg(player, "chat.market.enter_price"));
      }
      creationSessions.put(player.getUniqueId(), session.markAwaitingChat());
      chatSessions.put(
          player.getUniqueId(),
          new ChatSession(player.getUniqueId(), session.mode(), session.source()));
      player.closeInventory();
    } catch (ServiceException exception) {
      player.sendMessage(msg(player, "chat.market.cannot_continue",
          Map.of("reason", humanizeError(player, exception))));
    }
  }

  private void handleManageClick(Player player, int slot) {
    if (slot == 45) {
      openMainMenu(player);
      return;
    }
    if (slot == 49) {
      openManageMenu(player);
      return;
    }
    List<MarketService.ListingView> listings = marketService.listListingsForPlayer(player.getUniqueId(), 45);
    if (slot < 0 || slot >= listings.size()) {
      return;
    }
    openDetailMenu(player, listings.get(slot).id());
  }

  private void handleDetailClick(Player player, DetailHolder holder, int slot) {
    MarketService.ListingView listing = findOwnedListing(player, holder.listingId());
    if (listing == null) {
      player.sendMessage(msg(player, "chat.market.listing_missing"));
      openManageMenu(player);
      return;
    }
    try {
      if (slot == 36) {
        openManageMenu(player);
        return;
      }
      if (slot == 40) {
        if ("ACTIVE".equalsIgnoreCase(listing.status())) {
          marketService.pause(listing.sellerUserId(), listing.id());
          player.sendMessage(msg(player, "chat.market.paused", Map.of("listingId", listing.id())));
        } else if ("PAUSED".equalsIgnoreCase(listing.status())) {
          marketService.resume(listing.sellerUserId(), listing.id());
          player.sendMessage(msg(player, "chat.market.resumed", Map.of("listingId", listing.id())));
        }
        openDetailMenu(player, listing.id());
        return;
      }
      if (slot == 42) {
        MarketService.UnlistResult result = marketService.unlist(listing.sellerUserId(), listing.id());
        player.sendMessage(msg(player, "chat.market.unlisted", Map.of("listingId", result.listingId())));
        openManageMenu(player);
        return;
      }
      if (slot == 24 && listing.sourceMode() == MarketService.SupplyMode.SUPPLY) {
        MarketService.SupplyRefreshResult result =
            marketService.refreshSupplyListing(listing.sellerUserId(), listing.id());
        player.sendMessage(msg(player, "chat.market.refresh_success", Map.of(
            "loadedAmount", result.loadedAmount(),
            "currentStock", result.currentStock(),
            "maxStock", result.maxStock(),
            "loadedTotal", result.loadedTotal())));
        openDetailMenu(player, listing.id());
        return;
      }
      if (slot == 20) {
        player.sendMessage(msg(player, "chat.market.web_edit_hint"));
      }
    } catch (ServiceException exception) {
      player.sendMessage(msg(player, "chat.market.action_failed",
          Map.of("reason", humanizeError(player, exception))));
      openDetailMenu(player, listing.id());
    }
  }

  private void openInputInventory(
      Player player,
      ListingMode mode,
      MarketService.SupplySourceDescriptor source,
      String title) {
    clearTransientState(player);
    InputHolder holder = new InputHolder(player.getUniqueId(), mode);
    Inventory inventory = createInventory(holder, INPUT_SIZE, title);
    fillInputFrame(player, inventory, mode, source);
    creationSessions.put(player.getUniqueId(), new CreationSession(player.getUniqueId(), mode, inventory, source));
    player.openInventory(inventory);
  }

  private void openDetailMenu(Player player, long listingId) {
    MarketService.ListingView listing = findOwnedListing(player, listingId);
    if (listing == null) {
      player.sendMessage(msg(player, "chat.market.listing_missing"));
      openManageMenu(player);
      return;
    }
    DetailHolder holder = new DetailHolder(player.getUniqueId(), listingId);
    Inventory inventory = createInventory(holder, DETAIL_SIZE, msg(player, "gui.market.title.detail", Map.of("listingId", listing.id())));
    fill(inventory);
    inventory.setItem(4, buildListingIcon(player, listing));
    inventory.setItem(
        20,
        buildButton(
            Material.WRITABLE_BOOK,
            msg(player, "gui.market.detail.web_edit.title"),
            msg(player, "gui.market.detail.web_edit.subtitle"),
            msgList(player, "gui.market.detail.web_edit.lore")));
    if (listing.sourceMode() == MarketService.SupplyMode.SUPPLY) {
      inventory.setItem(
          24,
          buildButton(
              Material.HOPPER,
              msg(player, "gui.market.detail.refresh.title"),
              msg(player, "gui.market.detail.refresh.subtitle"),
              List.of(
                  msg(player, "gui.market.detail.refresh.transit",
                      Map.of("current", listing.quantity(), "max", listing.quantityTotal())),
                  msg(player, "gui.market.detail.refresh.batch", Map.of("value", safeInt(listing.supplyBatchSize()))),
                  msg(player, "gui.market.detail.refresh.loaded_total", Map.of("value", listing.supplyLoadedTotal())),
                  msg(player, "gui.market.detail.refresh.sold_total", Map.of("value", listing.supplySoldTotal())),
                  listing.supplyLastLoadedAt() == null
                      ? msg(player, "gui.market.detail.refresh.last_loaded_empty")
                      : msg(player, "gui.market.detail.refresh.last_loaded",
                          Map.of("value", TIME_FORMATTER.format(listing.supplyLastLoadedAt()))))));
    }
    if ("ACTIVE".equalsIgnoreCase(listing.status())) {
      inventory.setItem(40, buildButton(Material.REDSTONE_TORCH, msg(player, "gui.market.detail.pause.title"), msg(player, "gui.market.detail.pause.subtitle"), List.of()));
    } else if ("PAUSED".equalsIgnoreCase(listing.status())) {
      inventory.setItem(40, buildButton(Material.SOUL_TORCH, msg(player, "gui.market.detail.resume.title"), msg(player, "gui.market.detail.resume.subtitle"), List.of()));
    }
    inventory.setItem(42, buildButton(Material.BARRIER, msg(player, "gui.market.detail.unlist.title"), msg(player, "gui.market.detail.unlist.subtitle"), List.of()));
    inventory.setItem(36, buildButton(Material.ARROW, msg(player, "gui.market.detail.back.title"), msg(player, "gui.market.detail.back.subtitle"), List.of()));
    player.openInventory(inventory);
  }

  private MarketService.ListingView findOwnedListing(Player player, long listingId) {
    try {
      List<MarketService.ListingView> listings = marketService.listListingsForPlayer(player.getUniqueId(), 100);
      for (MarketService.ListingView listing : listings) {
        if (listing.id() == listingId) {
          return listing;
        }
      }
    } catch (ServiceException exception) {
      player.sendMessage(msg(player, "chat.market.read_failed",
          Map.of("reason", humanizeError(player, exception))));
    }
    return null;
  }

  private ItemStack extractManualItem(Inventory inventory) {
    List<ItemStack> items = collectContentItems(inventory);
    if (items.isEmpty()) {
      throw new ServiceException("empty_hand", "请先在箱子 GUI 中放入要上架的物品");
    }
    ItemStack first = null;
    int total = 0;
    for (ItemStack stack : items) {
      if (first == null) {
        first = stack.clone();
        first.setAmount(1);
      } else if (!sameTemplate(first, stack)) {
        throw new ServiceException("invalid_item", "普通上架一次只允许放入一种物品");
      }
      total += stack.getAmount();
    }
    if (first == null || total <= 0) {
      throw new ServiceException("invalid_item", "未检测到有效物品");
    }
    first.setAmount(total);
    return first;
  }

  private ItemStack extractSupplyTemplate(Inventory inventory) {
    List<ItemStack> items = collectContentItems(inventory);
    if (items.isEmpty()) {
      throw new ServiceException("invalid_item", "请先放入 1 个模板物品");
    }
    if (items.size() != 1 || items.get(0).getAmount() != 1) {
      throw new ServiceException("invalid_item", "供货箱上架只允许放入 1 个模板物品");
    }
    ItemStack template = items.get(0).clone();
    template.setAmount(1);
    return template;
  }

  private List<ItemStack> collectContentItems(Inventory inventory) {
    List<ItemStack> items = new ArrayList<>();
    for (int slot = 0; slot < 45; slot++) {
      ItemStack stack = inventory.getItem(slot);
      if (stack == null || stack.getType() == Material.AIR) {
        continue;
      }
      items.add(stack.clone());
    }
    return items;
  }

  private boolean sameTemplate(ItemStack a, ItemStack b) {
    ItemStack left = a.clone();
    ItemStack right = b.clone();
    left.setAmount(1);
    right.setAmount(1);
    return left.isSimilar(right);
  }

  @SuppressFBWarnings(
      value = "DB_DUPLICATE_BRANCHES",
      justification = "Manual and supply listing modes intentionally share the same button layout")
  private void fillInputFrame(
      Player player,
      Inventory inventory,
      ListingMode mode,
      MarketService.SupplySourceDescriptor source) {
    fill(inventory);
    for (int slot = 0; slot < 45; slot++) {
      inventory.setItem(slot, null);
    }
    inventory.setItem(
        45,
        buildButton(
            Material.ARROW,
            msg(player, "gui.market.input.cancel.title"),
            msg(player, "gui.market.input.cancel.subtitle"),
            msgList(player, "gui.market.input.cancel.lore")));
    inventory.setItem(
        49,
        buildButton(
            Material.LIME_WOOL,
            msg(player, "gui.market.input.next.title"),
            msg(player, "gui.market.input.next.subtitle"),
            mode == ListingMode.MANUAL
                ? msgList(player, "gui.market.input.next.manual_lore")
                : List.of(
                    msg(player, "gui.market.input.next.supply.lore_template"),
                    msg(player, "gui.market.input.next.supply.lore_currency"),
                    source == null
                        ? msg(player, "gui.market.input.next.supply.lore_source_missing")
                        : msg(player, "gui.market.input.next.supply.lore_source", Map.of(
                            "world", source.worldName(),
                            "x", source.x(),
                            "y", source.y(),
                            "z", source.z())))));
    inventory.setItem(
        53,
        buildButton(
            mode == ListingMode.MANUAL ? Material.CHEST : Material.HOPPER,
            mode == ListingMode.MANUAL
                ? msg(player, "gui.market.input.info.manual.title")
                : msg(player, "gui.market.input.info.supply.title"),
            mode == ListingMode.MANUAL
                ? msg(player, "gui.market.input.info.manual.subtitle")
                : msg(player, "gui.market.input.info.supply.subtitle"),
            mode == ListingMode.MANUAL
                ? msgList(player, "gui.market.input.info.manual.lore")
                : msgList(player, "gui.market.input.info.supply.lore")));
  }

  private void returnItems(Player player, Inventory inventory) {
    for (int slot = 0; slot < 45; slot++) {
      ItemStack stack = inventory.getItem(slot);
      if (stack == null || stack.getType() == Material.AIR) {
        continue;
      }
      inventory.setItem(slot, null);
      player.getInventory().addItem(stack)
          .values()
          .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }
  }

  private void cancelChatSession(Player player, String message) {
    chatSessions.remove(player.getUniqueId());
    CreationSession session = creationSessions.remove(player.getUniqueId());
    if (session != null) {
      returnItems(player, session.inventory());
    }
    player.sendMessage(message);
    openMainMenu(player);
  }

  private void clearTransientState(Player player) {
    chatSessions.remove(player.getUniqueId());
    supplyBindSessions.remove(player.getUniqueId());
    CreationSession session = creationSessions.remove(player.getUniqueId());
    if (session != null && !session.awaitingChat()) {
      returnItems(player, session.inventory());
    }
  }

  private Inventory createInventory(GuiHolder holder, int size, String title) {
    Inventory inventory = Bukkit.createInventory(holder, size, title);
    holder.bind(inventory);
    return inventory;
  }

  private void fill(Inventory inventory) {
    ItemStack filler = buildButton(Material.GRAY_STAINED_GLASS_PANE, " ", "", List.of());
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      inventory.setItem(slot, filler);
    }
  }

  private ItemStack buildListingIcon(Player player, MarketService.ListingView listing) {
    Material material = Material.matchMaterial(String.valueOf(listing.itemMaterial()));
    ItemStack item = new ItemStack(material == null || material == Material.AIR ? Material.CHEST : material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName("§f#" + listing.id() + " §7" + listing.itemMaterial());
      List<String> lore = new ArrayList<>();
      lore.add("§7" + msg(player, "gui.market.listing.status", Map.of("value", marketStatusLabel(player, listing.status()))));
      lore.add("§7" + msg(player, "gui.market.listing.price", Map.of("price", listing.price(), "currency", listing.currency().name())));
      lore.add("§7" + msg(player, "gui.market.listing.stock", Map.of("current", listing.quantity(), "max", listing.quantityTotal())));
      if (listing.sourceMode() == MarketService.SupplyMode.SUPPLY) {
        lore.add("§7" + msg(player, "gui.market.listing.mode", Map.of("value", msg(player, "enum.market_mode.supply"))));
        lore.add("§7" + msg(player, "gui.market.listing.batch", Map.of("value", safeInt(listing.supplyBatchSize()))));
        lore.add("§7" + msg(player, "gui.market.listing.loaded_total", Map.of("value", listing.supplyLoadedTotal())));
      } else {
        lore.add("§7" + msg(player, "gui.market.listing.mode", Map.of("value", msg(player, "enum.market_mode.manual"))));
      }
      if (listing.remark() != null && !listing.remark().isBlank()) {
        lore.add("§7" + msg(player, "gui.market.listing.remark", Map.of("value", trimRemark(listing.remark()))));
      } else {
        lore.add("§7" + msg(player, "gui.market.listing.remark_placeholder"));
      }
      lore.add("§7" + msg(player, "gui.market.listing.created_at",
          Map.of("value", TIME_FORMATTER.format(listing.createdAt()))));
      lore.add("§e" + msg(player, "gui.market.listing.click_open"));
      meta.setLore(lore);
      meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
      item.setItemMeta(meta);
    }
    return item;
  }

  private ItemStack buildButton(Material material, String title, String subtitle, List<String> loreLines) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName("§f" + title);
      List<String> lore = new ArrayList<>();
      if (subtitle != null && !subtitle.isBlank()) {
        lore.add("§7" + subtitle);
      }
      for (String line : loreLines) {
        lore.add("§7" + line);
      }
      meta.setLore(lore);
      meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
      item.setItemMeta(meta);
    }
    return item;
  }

  private int safeInt(Integer value) {
    return value == null ? 0 : value;
  }

  private String trimRemark(String remark) {
    return remark.length() <= 40 ? remark : remark.substring(0, 37) + "...";
  }

  enum ListingMode {
    MANUAL,
    SUPPLY
  }

  enum GuiKind {
    MAIN,
    INPUT,
    MANAGE,
    DETAIL
  }

  static abstract class GuiHolder implements InventoryHolder {
    private final UUID playerId;
    private final GuiKind kind;
    private Inventory inventory;

    GuiHolder(UUID playerId, GuiKind kind) {
      this.playerId = playerId;
      this.kind = kind;
    }

    UUID playerId() {
      return playerId;
    }

    GuiKind kind() {
      return kind;
    }

    void bind(Inventory inventory) {
      this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  private static final class MainHolder extends GuiHolder {
    MainHolder(UUID playerId) {
      super(playerId, GuiKind.MAIN);
    }
  }

  private static final class InputHolder extends GuiHolder {
    private final ListingMode mode;

    InputHolder(UUID playerId, ListingMode mode) {
      super(playerId, GuiKind.INPUT);
      this.mode = mode;
    }

    ListingMode mode() {
      return mode;
    }
  }

  private static final class ManageHolder extends GuiHolder {
    ManageHolder(UUID playerId) {
      super(playerId, GuiKind.MANAGE);
    }
  }

  private static final class DetailHolder extends GuiHolder {
    private final long listingId;

    DetailHolder(UUID playerId, long listingId) {
      super(playerId, GuiKind.DETAIL);
      this.listingId = listingId;
    }

    long listingId() {
      return listingId;
    }
  }

  private record CreationSession(
      UUID playerId,
      ListingMode mode,
      Inventory inventory,
      MarketService.SupplySourceDescriptor source,
      boolean awaitingChat) {

    CreationSession(UUID playerId, ListingMode mode, Inventory inventory, MarketService.SupplySourceDescriptor source) {
      this(playerId, mode, inventory, source, false);
    }

    CreationSession markAwaitingChat() {
      return new CreationSession(playerId, mode, inventory, source, true);
    }
  }

  private record ChatSession(
      UUID playerId,
      ListingMode mode,
      MarketService.SupplySourceDescriptor source) {
  }

  private record SupplyBindSession(UUID playerId) {
  }
}
