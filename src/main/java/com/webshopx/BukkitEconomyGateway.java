package com.webshopx;

import com.webshopx.platform.EconomyGateway;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

final class BukkitEconomyGateway implements EconomyGateway {

  private final JavaPlugin plugin;

  BukkitEconomyGateway(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public String providerName() {
    Economy economy = resolveEconomy();
    return economy == null ? null : economy.getName();
  }

  @Override
  public long readBalance(String accountName) {
    Economy economy = resolveEconomy();
    if (economy == null) {
      throw new ServiceException("vault_unavailable", "Vault economy provider is unavailable");
    }
    double balance = economy.getBalance(accountName);
    return Math.round(balance);
  }

  @Override
  public long applyDelta(String accountName, long delta, boolean enforceBalance) {
    Economy economy = resolveEconomy();
    if (economy == null) {
      throw new ServiceException("vault_unavailable", "Vault economy provider is unavailable");
    }
    if (delta < 0 && enforceBalance && economy.getBalance(accountName) + delta < 0) {
      throw new ServiceException("insufficient_balance", "Insufficient game coin balance");
    }
    EconomyResponse response = delta >= 0
        ? economy.depositPlayer(accountName, delta)
        : economy.withdrawPlayer(accountName, -delta);
    if (response == null || !response.transactionSuccess()) {
      String message = response == null ? "Vault transaction failed" : response.errorMessage;
      throw new ServiceException("vault_failure", message == null ? "Vault transaction failed" : message);
    }
    return Math.round(response.balance);
  }

  @Override
  public boolean available() {
    return resolveEconomy() != null;
  }

  private Economy resolveEconomy() {
    Plugin vaultPlugin = plugin.getServer().getPluginManager().getPlugin("Vault");
    if (vaultPlugin == null || !vaultPlugin.isEnabled()) {
      return null;
    }
    RegisteredServiceProvider<Economy> provider = plugin.getServer().getServicesManager().getRegistration(Economy.class);
    return provider == null ? null : provider.getProvider();
  }
}
