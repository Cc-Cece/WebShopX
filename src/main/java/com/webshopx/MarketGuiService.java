package com.webshopx;

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
  private final Map<UUID, CreationSession> creationSessions = new HashMap<>();
  private final Map<UUID, ChatSession> chatSessions = new HashMap<>();
  private final Map<UUID, SupplyBindSession> supplyBindSessions = new HashMap<>();

  MarketGuiService(MarketService marketService, Supplier<PluginSettings> settingsSupplier) {
    this.marketService = marketService;
    this.settingsSupplier = settingsSupplier;
  }

  void openMainMenu(Player player) {
    MainHolder holder = new MainHolder(player.getUniqueId());
    Inventory inventory = createInventory(holder, MAIN_SIZE, "市场中心");
    fill(inventory);
    inventory.setItem(
        11,
        buildButton(
            Material.CHEST,
            "普通上架",
            "放入物品后输入单价即可创建",
            List.of("默认币种：GAME_COIN", "备注和币种后续到网页修改")));
    inventory.setItem(
        13,
        buildButton(
            Material.HOPPER,
            "供货箱上架",
            "先点击供货箱，再放入 1 个模板物品",
            List.of("支持大箱子、箱子、木桶、潜影盒", "默认使用服务器配置的提取量与中转上限")));
    inventory.setItem(
        15,
        buildButton(
            Material.BOOK,
            "我的上架",
            "查看并管理当前上架",
            List.of("支持暂停 / 恢复 / 下架", "供货箱模式支持手动刷新")));
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
      cancelChatSession(player, "已取消市场创建流程。");
      return;
    }
    if (trimmed.isBlank()) {
      player.sendMessage("§c请输入有效价格，或输入 cancel 取消。");
      return;
    }
    long price;
    try {
      price = Long.parseLong(trimmed);
    } catch (NumberFormatException exception) {
      player.sendMessage("§c价格必须为正整数，或输入 cancel 取消。");
      return;
    }
    if (price <= 0) {
      player.sendMessage("§c价格必须大于 0。");
      return;
    }

    chatSessions.remove(player.getUniqueId());
    CreationSession creation = creationSessions.remove(player.getUniqueId());
    if (creation == null) {
      player.sendMessage("§c市场创建状态已失效，请重新开始。");
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
        player.sendMessage(
            "§a上架成功：§f#" + result.listingId()
                + " §7| 物品 §f" + result.material()
                + " x" + result.quantity()
                + " §7| 单价 §f" + result.price() + " GAME_COIN");
      } else {
        ItemStack template = extractSupplyTemplate(creation.inventory());
        returnItems(player, creation.inventory());
        MarketService.ListingCreateResult result = marketService.createSupplyListingFromTemplate(
            player,
            session.source(),
            template,
            price,
            CurrencyType.GAME_COIN);
        player.sendMessage(
            "§a供货箱上架成功：§f#" + result.listingId()
                + " §7| 模板 §f" + result.material()
                + " §7| 当前中转 §f" + result.quantity()
                + " §7| 单价 §f" + result.price() + " GAME_COIN");
      }
      openManageMenu(player);
    } catch (ServiceException exception) {
      player.sendMessage("§c创建失败：" + exception.getMessage());
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
    player.sendMessage("§e请右键点击要绑定的供货箱（支持大箱子、箱子、木桶、潜影盒）。输入 /ws market 可重新打开菜单。");
  }

  void handleSupplyBindClick(Player player, Block block) {
    SupplyBindSession bindSession = supplyBindSessions.remove(player.getUniqueId());
    if (bindSession == null) {
      return;
    }
    try {
      MarketService.SupplySourceDescriptor source = marketService.describeSupplySource(block);
      openInputInventory(player, ListingMode.SUPPLY, source, "供货模板");
      player.sendMessage("§a供货箱已绑定。§e请放入 1 个模板物品后点击“下一步”。");
    } catch (ServiceException exception) {
      player.sendMessage("§c绑定失败：" + exception.getMessage());
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
      player.sendMessage("§c读取上架失败：" + exception.getMessage());
      openMainMenu(player);
      return;
    }
    ManageHolder holder = new ManageHolder(player.getUniqueId());
    Inventory inventory = createInventory(holder, MANAGE_SIZE, "我的上架");
    fill(inventory);
    if (listings.isEmpty()) {
      inventory.setItem(
          22,
          buildButton(Material.BARRIER, "暂无上架", "当前没有可管理的上架", List.of("可先返回主菜单创建上架")));
    } else {
      for (int index = 0; index < Math.min(45, listings.size()); index++) {
        inventory.setItem(index, buildListingIcon(listings.get(index)));
      }
    }
    inventory.setItem(45, buildButton(Material.ARROW, "返回", "回到市场中心", List.of()));
    inventory.setItem(49, buildButton(Material.SUNFLOWER, "刷新列表", "重新读取最新状态", List.of()));
    player.openInventory(inventory);
  }

  private void handleMainClick(Player player, int slot) {
    if (slot == 11) {
      openInputInventory(player, ListingMode.MANUAL, null, "普通上架");
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
        player.sendMessage("§e请输入单价，或输入 §ccancel §e取消。");
      } else {
        extractSupplyTemplate(session.inventory());
        player.sendMessage("§e请输入单价，或输入 §ccancel §e取消。");
      }
      creationSessions.put(player.getUniqueId(), session.markAwaitingChat());
      chatSessions.put(
          player.getUniqueId(),
          new ChatSession(player.getUniqueId(), session.mode(), session.source()));
      player.closeInventory();
    } catch (ServiceException exception) {
      player.sendMessage("§c无法继续：" + exception.getMessage());
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
      player.sendMessage("§c该上架已不存在，请重新打开列表。");
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
          player.sendMessage("§e已暂停上架 #" + listing.id());
        } else if ("PAUSED".equalsIgnoreCase(listing.status())) {
          marketService.resume(listing.sellerUserId(), listing.id());
          player.sendMessage("§a已恢复上架 #" + listing.id());
        }
        openDetailMenu(player, listing.id());
        return;
      }
      if (slot == 42) {
        MarketService.UnlistResult result = marketService.unlist(listing.sellerUserId(), listing.id());
        player.sendMessage("§6已下架 #" + result.listingId() + "，库存退回处理中。");
        openManageMenu(player);
        return;
      }
      if (slot == 24 && listing.sourceMode() == MarketService.SupplyMode.SUPPLY) {
        MarketService.SupplyRefreshResult result =
            marketService.refreshSupplyListing(listing.sellerUserId(), listing.id());
        player.sendMessage(
            "§a供货刷新完成：本次提取 §f" + result.loadedAmount()
                + " §7| 当前中转 §f" + result.currentStock()
                + "/" + result.maxStock()
                + " §7| 累计提取 §f" + result.loadedTotal());
        openDetailMenu(player, listing.id());
        return;
      }
      if (slot == 20) {
        player.sendMessage("§e价格、币种、备注与供货参数请前往网页端编辑。");
      }
    } catch (ServiceException exception) {
      player.sendMessage("§c操作失败：" + exception.getMessage());
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
    fillInputFrame(inventory, mode, source);
    creationSessions.put(player.getUniqueId(), new CreationSession(player.getUniqueId(), mode, inventory, source));
    player.openInventory(inventory);
  }

  private void openDetailMenu(Player player, long listingId) {
    MarketService.ListingView listing = findOwnedListing(player, listingId);
    if (listing == null) {
      player.sendMessage("§c该上架已不存在，请重新打开列表。");
      openManageMenu(player);
      return;
    }
    DetailHolder holder = new DetailHolder(player.getUniqueId(), listingId);
    Inventory inventory = createInventory(holder, DETAIL_SIZE, "上架 #" + listing.id());
    fill(inventory);
    inventory.setItem(4, buildListingIcon(listing));
    inventory.setItem(
        20,
        buildButton(
            Material.WRITABLE_BOOK,
            "网页编辑",
            "价格、币种、备注与供货参数只在网页编辑",
            List.of("游戏内保持最少输入", "网页端可补充更多设置")));
    if (listing.sourceMode() == MarketService.SupplyMode.SUPPLY) {
      inventory.setItem(
          24,
          buildButton(
              Material.HOPPER,
              "手动刷新供货",
              "检查供货箱并补货一次",
              List.of(
                  "当前中转：" + listing.quantity() + "/" + listing.quantityTotal(),
                  "单次提取：" + safeInt(listing.supplyBatchSize()),
                  "累计提取：" + listing.supplyLoadedTotal(),
                  "累计售出：" + listing.supplySoldTotal(),
                  listing.supplyLastLoadedAt() == null
                      ? "最近补货：未补货"
                      : "最近补货：" + TIME_FORMATTER.format(listing.supplyLastLoadedAt()))));
    }
    if ("ACTIVE".equalsIgnoreCase(listing.status())) {
      inventory.setItem(40, buildButton(Material.REDSTONE_TORCH, "暂停上架", "暂时停止出售", List.of()));
    } else if ("PAUSED".equalsIgnoreCase(listing.status())) {
      inventory.setItem(40, buildButton(Material.SOUL_TORCH, "恢复上架", "重新对外出售", List.of()));
    }
    inventory.setItem(42, buildButton(Material.BARRIER, "下架退回", "将当前库存退回游戏内", List.of()));
    inventory.setItem(36, buildButton(Material.ARROW, "返回列表", "回到我的上架", List.of()));
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
      player.sendMessage("§c读取上架失败：" + exception.getMessage());
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

  private void fillInputFrame(
      Inventory inventory,
      ListingMode mode,
      MarketService.SupplySourceDescriptor source) {
    fill(inventory);
    for (int slot = 0; slot < 45; slot++) {
      inventory.setItem(slot, null);
    }
    inventory.setItem(
        45,
        buildButton(Material.ARROW, "取消", "取消流程并退回物品", List.of("输入 cancel 也可取消聊天流程")));
    inventory.setItem(
        49,
        buildButton(
            Material.LIME_WOOL,
            "下一步",
            mode == ListingMode.MANUAL ? "关闭 GUI 后在聊天栏输入单价" : "关闭 GUI 后在聊天栏输入单价",
            mode == ListingMode.MANUAL
                ? List.of("放多少个就上架多少个", "默认币种：GAME_COIN")
                : List.of(
                    "只放 1 个模板物品",
                    "默认币种：GAME_COIN",
                    source == null
                        ? "供货箱：未绑定"
                        : "供货箱：" + source.worldName() + " " + source.x() + " " + source.y() + " " + source.z())));
    inventory.setItem(
        53,
        buildButton(
            mode == ListingMode.MANUAL ? Material.CHEST : Material.HOPPER,
            mode == ListingMode.MANUAL ? "普通上架" : "供货箱上架",
            mode == ListingMode.MANUAL ? "把要出售的物品放入前 45 格" : "把 1 个模板物品放入前 45 格",
            mode == ListingMode.MANUAL
                ? List.of("只允许一种物品", "其他设置后续在网页编辑")
                : List.of("其他参数默认取服务器配置", "后续在网页编辑供货参数和备注")));
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
    player.sendMessage("§e" + message);
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

  private ItemStack buildListingIcon(MarketService.ListingView listing) {
    Material material = Material.matchMaterial(String.valueOf(listing.itemMaterial()));
    ItemStack item = new ItemStack(material == null || material == Material.AIR ? Material.CHEST : material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName("§f#" + listing.id() + " §7" + listing.itemMaterial());
      List<String> lore = new ArrayList<>();
      lore.add("§7状态：§f" + listing.status());
      lore.add("§7价格：§f" + listing.price() + " " + listing.currency().name());
      lore.add("§7库存：§f" + listing.quantity() + "/" + listing.quantityTotal());
      if (listing.sourceMode() == MarketService.SupplyMode.SUPPLY) {
        lore.add("§7模式：§f供货箱");
        lore.add("§7单次提取：§f" + safeInt(listing.supplyBatchSize()));
        lore.add("§7累计提取：§f" + listing.supplyLoadedTotal());
      } else {
        lore.add("§7模式：§f普通上架");
      }
      if (listing.remark() != null && !listing.remark().isBlank()) {
        lore.add("§7备注：§f" + trimRemark(listing.remark()));
      } else {
        lore.add("§7备注：§8网页端可编辑");
      }
      lore.add("§7创建时间：§f" + TIME_FORMATTER.format(listing.createdAt()));
      lore.add("§e点击打开详情");
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
