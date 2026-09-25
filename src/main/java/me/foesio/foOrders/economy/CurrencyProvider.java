package me.foesio.foOrders.economy;

import org.bukkit.OfflinePlayer;

/**
 * Balance operations for one order currency. Implementations wrap an economy
 * plugin (Vault, ExcellentEconomy, ...) and are looked up again on every
 * reload so a late-loading economy plugin can still be picked up.
 */
public interface CurrencyProvider {
    boolean isAvailable();

    /**
     * Whether the backing economy accepts fractional amounts. Whole-number
     * currencies reject fractional prices when an order is created.
     */
    boolean supportsDecimals();

    boolean has(OfflinePlayer player, double amount);

    boolean withdraw(OfflinePlayer player, double amount);

    boolean deposit(OfflinePlayer player, double amount);

    static CurrencyProvider unavailable() {
        return UnavailableCurrencyProvider.INSTANCE;
    }
}

enum UnavailableCurrencyProvider implements CurrencyProvider {
    INSTANCE;

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean supportsDecimals() {
        return true;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return false;
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        return false;
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        return false;
    }
}
