package com.webshopx;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper-only Vault adapter for the platform-neutral wallet service. */
final class VaultGameCoinProvider implements WalletService.GameCoinProvider {
  private final JavaPlugin plugin;
  private volatile Economy economy;
  private volatile String providerName;

  VaultGameCoinProvider(JavaPlugin plugin) {
    this.plugin = plugin;
    refresh();
  }

  @Override public void refresh() {
    Plugin vault = plugin.getServer().getPluginManager().getPlugin("Vault");
    if (vault == null || !vault.isEnabled()) {
      economy = null;
      providerName = null;
      return;
    }
    RegisteredServiceProvider<Economy> registration =
        plugin.getServer().getServicesManager().getRegistration(Economy.class);
    economy = registration == null ? null : registration.getProvider();
    providerName = economy == null ? null : economy.getName();
  }

  @Override public IntegrationStatus status() {
    boolean present = plugin.getServer().getPluginManager().getPlugin("Vault") != null;
    Economy current = economy;
    return new IntegrationStatus(present, current != null, providerName);
  }

  @Override public long balance(String accountName) {
    Economy current = requireEconomy();
    return toCoins(current.getBalance(accountName));
  }

  @Override public long apply(String accountName, long delta, boolean enforceBalance) {
    Economy current = requireEconomy();
    double existing = current.getBalance(accountName);
    if (enforceBalance && existing + delta < 0.0D) {
      throw new ServiceException("insufficient_funds", "Wallet balance is insufficient");
    }
    EconomyResponse response = delta >= 0L
        ? current.depositPlayer(accountName, delta)
        : current.withdrawPlayer(accountName, -delta);
    if (!response.transactionSuccess()) {
      String message = response.errorMessage == null || response.errorMessage.isBlank()
          ? "Vault transaction failed" : response.errorMessage;
      throw new ServiceException("vault_error", message);
    }
    return toCoins(current.getBalance(accountName));
  }

  private Economy requireEconomy() {
    Economy current = economy;
    if (current == null) throw new ServiceException("vault_unavailable", "Vault economy provider is unavailable");
    return current;
  }

  private static long toCoins(double value) {
    if (!Double.isFinite(value) || value <= 0.0D) return 0L;
    double floor = Math.floor(value);
    return floor >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) floor;
  }
}
