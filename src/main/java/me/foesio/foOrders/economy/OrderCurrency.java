package me.foesio.foOrders.economy;

import me.foesio.foOrders.util.TextFormat;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One currency players can pay orders with. The id is what gets stored on each
 * order, so it must stay stable once orders exist in it.
 */
public final class OrderCurrency {
    public static final String AMOUNT_PLACEHOLDER = "{amount}";
    private static final double WHOLE_NUMBER_EPSILON = 1.0E-9D;

    private final String id;
    private final String displayName;
    private final String format;
    private final Material icon;
    private final boolean decimal;
    private final double taxPercentage;
    private final CurrencyProvider provider;
    private final boolean selectable;
    private final Supplier<String> themeColor;

    OrderCurrency(
        String id,
        String displayName,
        String format,
        Material icon,
        boolean decimal,
        double taxPercentage,
        CurrencyProvider provider,
        boolean selectable,
        Supplier<String> themeColor
    ) {
        this.id = Objects.requireNonNull(normalizeId(id), "id");
        this.displayName = displayName == null || displayName.isBlank() ? this.id : displayName;
        this.format = format == null || format.isBlank() ? AMOUNT_PLACEHOLDER + " " + this.displayName : format;
        this.icon = icon == null ? Material.GOLD_INGOT : icon;
        this.decimal = decimal;
        this.taxPercentage = taxPercentage;
        this.provider = provider == null ? CurrencyProvider.unavailable() : provider;
        this.selectable = selectable;
        this.themeColor = themeColor;
    }

    public static String normalizeId(String rawId) {
        if (rawId == null) {
            return null;
        }
        String normalized = rawId.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public static boolean isWholeNumber(double value) {
        return Double.isFinite(value) && Math.abs(value - Math.rint(value)) < WHOLE_NUMBER_EPSILON;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Material icon() {
        return icon;
    }

    /** Whether fractional prices are accepted for this currency. */
    public boolean decimal() {
        return decimal;
    }

    /** Creation tax override in percent, or a negative value to use the global order tax. */
    public double taxPercentage() {
        return taxPercentage;
    }

    /** Whether players may pick this currency for new orders right now. */
    public boolean selectable() {
        return selectable && isAvailable();
    }

    public boolean isAvailable() {
        return provider.isAvailable();
    }

    public boolean has(OfflinePlayer player, double amount) {
        return provider.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        return provider.withdraw(player, amount);
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        return provider.deposit(player, amount);
    }

    /**
     * Applies the configured display format to an already formatted number.
     * Colour codes in the format are kept so GUIs and chat can colourise them.
     */
    public String format(String amountText) {
        String safeAmount = amountText == null ? "" : amountText;
        String formatted = format
            .replace(AMOUNT_PLACEHOLDER, safeAmount)
            .replace("%amount%", safeAmount);
        String theme = themeColor == null ? null : themeColor.get();
        return formatted.replace("{theme}", theme == null ? "" : theme);
    }

    /** Same as {@link #format(String)} with every colour code removed, for logs, history and webhooks. */
    public String formatPlain(String amountText) {
        String stripped = ChatColor.stripColor(TextFormat.colorize(format(amountText)));
        return stripped == null ? "" : stripped.trim();
    }

    @Override
    public String toString() {
        return "OrderCurrency[" + id + "]";
    }
}
