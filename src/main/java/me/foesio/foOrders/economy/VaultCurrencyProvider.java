package me.foesio.foOrders.economy;

import me.foesio.core.economy.VaultEconomyBridge;
import org.bukkit.OfflinePlayer;

/**
 * The primary server economy reached through Vault. This is the currency every
 * order created before currency support existed is stored in.
 */
public final class VaultCurrencyProvider implements CurrencyProvider {
    private final VaultEconomyBridge bridge;

    public VaultCurrencyProvider(VaultEconomyBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public boolean isAvailable() {
        return bridge != null && bridge.isAvailable();
    }

    @Override
    public boolean supportsDecimals() {
        return true;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return isAvailable() && bridge.has(player, amount);
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        return isAvailable() && bridge.withdraw(player, amount);
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        return isAvailable() && bridge.deposit(player, amount);
    }
}
