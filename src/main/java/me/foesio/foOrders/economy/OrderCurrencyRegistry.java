package me.foesio.foOrders.economy;

import me.foesio.core.economy.VaultEconomyBridge;
import me.foesio.core.logging.FoFileLogger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Loads the {@code currencies} config section and hands out {@link OrderCurrency}
 * instances. Disabled currencies are still loaded (only not selectable) so orders
 * created in them earlier can still be delivered, cancelled and refunded.
 */
public final class OrderCurrencyRegistry {
    public static final String VAULT_ID = "vault";
    public static final String CONFIG_PATH = "currencies";
    private static final String PROVIDER_VAULT = "vault";
    private static final String PROVIDER_EXCELLENT_ECONOMY = "excellenteconomy";
    private static final String DEFAULT_VAULT_FORMAT = "${amount}";
    private static final String DEFAULT_VAULT_NAME = "Money";
    private static final double MAX_TAX_PERCENTAGE = 100D;

    private final Map<String, OrderCurrency> placeholders = new ConcurrentHashMap<>();
    private volatile Map<String, OrderCurrency> byId = Map.of();
    private volatile List<OrderCurrency> selectable = List.of();
    private volatile Supplier<String> themeColor;

    public synchronized void reload(
        ConfigurationSection section,
        VaultEconomyBridge vault,
        Supplier<String> themeColor,
        Logger logger,
        FoFileLogger fileLogger
    ) {
        this.themeColor = themeColor;
        placeholders.clear();
        Map<String, OrderCurrency> loaded = new LinkedHashMap<>();
        List<OrderCurrency> loadedSelectable = new ArrayList<>();

        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) {
                    continue;
                }
                String id = OrderCurrency.normalizeId(key);
                if (id == null) {
                    continue;
                }
                if (loaded.containsKey(id)) {
                    warn(logger, fileLogger, "Duplicate currency id '" + id + "' in " + CONFIG_PATH + "; keeping the first one.");
                    continue;
                }
                OrderCurrency currency = load(id, entry, vault, themeColor, logger, fileLogger, loadedSelectable);
                loaded.put(id, currency);
            }
        }

        if (!loaded.containsKey(VAULT_ID)) {
            // Orders created before currency support are stored as "vault"; keep them working
            // even when the section was trimmed down by the server owner.
            loaded.put(VAULT_ID, defaultVaultCurrency(vault, themeColor, false));
        }

        if (loadedSelectable.isEmpty()) {
            OrderCurrency vaultCurrency = loaded.get(VAULT_ID);
            if (vaultCurrency.isAvailable()) {
                vaultCurrency = new OrderCurrency(
                    VAULT_ID,
                    vaultCurrency.displayName(),
                    vaultCurrencyFormat(section),
                    vaultCurrency.icon(),
                    vaultCurrency.decimal(),
                    vaultCurrency.taxPercentage(),
                    new VaultCurrencyProvider(vault),
                    true,
                    themeColor
                );
                loaded.put(VAULT_ID, vaultCurrency);
                loadedSelectable.add(vaultCurrency);
                warn(logger, fileLogger, "No currency in " + CONFIG_PATH + " is enabled and available; falling back to the Vault economy for new orders.");
            } else {
                warn(logger, fileLogger, "No currency in " + CONFIG_PATH + " is available; players cannot create orders until an economy is available.");
            }
        }

        byId = Collections.unmodifiableMap(loaded);
        selectable = List.copyOf(loadedSelectable);
    }

    private OrderCurrency load(
        String id,
        ConfigurationSection entry,
        VaultEconomyBridge vault,
        Supplier<String> themeColor,
        Logger logger,
        FoFileLogger fileLogger,
        List<OrderCurrency> loadedSelectable
    ) {
        boolean enabled = entry.getBoolean("enabled", true);
        String defaultProvider = VAULT_ID.equals(id) ? PROVIDER_VAULT : PROVIDER_EXCELLENT_ECONOMY;
        String providerName = normalizeProvider(entry.getString("provider", defaultProvider));
        String displayName = entry.getString("name", capitalize(id));
        String format = entry.getString("format", OrderCurrency.AMOUNT_PLACEHOLDER + " " + displayName);
        Material icon = parseMaterial(entry.getString("icon"), logger, fileLogger, id);
        boolean decimal = entry.getBoolean("decimal", true);
        double taxPercentage = parseTaxPercentage(entry, id, logger, fileLogger);

        CurrencyProvider provider;
        String unavailableReason = null;
        boolean quiet = false;
        switch (providerName) {
            case PROVIDER_VAULT -> provider = new VaultCurrencyProvider(vault);
            case PROVIDER_EXCELLENT_ECONOMY -> {
                String currencyId = entry.getString("currency-id", id);
                ExcellentEconomyCurrencyProvider.Resolution resolution = ExcellentEconomyCurrencyProvider.resolve(currencyId, logger);
                if (resolution.provider() != null) {
                    provider = resolution.provider();
                } else {
                    provider = CurrencyProvider.unavailable();
                    unavailableReason = resolution.failureReason();
                    quiet = resolution.pluginMissing();
                }
            }
            default -> {
                provider = CurrencyProvider.unavailable();
                unavailableReason = "unknown provider '" + providerName + "' (use vault or excellenteconomy)";
            }
        }

        boolean effectiveDecimal = decimal && provider.supportsDecimals();
        OrderCurrency currency = new OrderCurrency(
            id,
            displayName,
            format,
            icon,
            effectiveDecimal,
            taxPercentage,
            provider,
            enabled,
            themeColor
        );

        if (!enabled) {
            return currency;
        }
        if (provider.isAvailable()) {
            loadedSelectable.add(currency);
            return currency;
        }
        String reason = unavailableReason == null ? "its economy provider is not available" : unavailableReason;
        String message = "Currency '" + id + "' is enabled but cannot be used for new orders: " + reason + ".";
        if (quiet) {
            info(logger, fileLogger, message);
        } else {
            warn(logger, fileLogger, message);
        }
        return currency;
    }

    private String vaultCurrencyFormat(ConfigurationSection section) {
        ConfigurationSection vaultSection = section == null ? null : section.getConfigurationSection(VAULT_ID);
        return vaultSection == null ? DEFAULT_VAULT_FORMAT : vaultSection.getString("format", DEFAULT_VAULT_FORMAT);
    }

    private OrderCurrency defaultVaultCurrency(VaultEconomyBridge vault, Supplier<String> themeColor, boolean selectable) {
        return new OrderCurrency(
            VAULT_ID,
            DEFAULT_VAULT_NAME,
            DEFAULT_VAULT_FORMAT,
            Material.EMERALD,
            true,
            -1D,
            new VaultCurrencyProvider(vault),
            selectable,
            themeColor
        );
    }

    /** Currencies players may pick for a new order, in config order. */
    public List<OrderCurrency> selectable() {
        return selectable;
    }

    public boolean hasChoice() {
        return selectable.size() > 1;
    }

    /** The currency new orders start in: the first enabled and available one. */
    public OrderCurrency defaultCurrency() {
        List<OrderCurrency> current = selectable;
        if (!current.isEmpty()) {
            return current.get(0);
        }
        return resolve(VAULT_ID);
    }

    /** A selectable currency by id, or {@code null} when it is unknown, disabled or unavailable. */
    public OrderCurrency selectable(String id) {
        String normalized = OrderCurrency.normalizeId(id);
        if (normalized == null) {
            return null;
        }
        for (OrderCurrency currency : selectable) {
            if (currency.id().equals(normalized)) {
                return currency;
            }
        }
        return null;
    }

    /** The selectable currency after {@code currentId}, wrapping around. */
    public OrderCurrency next(String currentId) {
        List<OrderCurrency> current = selectable;
        if (current.isEmpty()) {
            return defaultCurrency();
        }
        String normalized = OrderCurrency.normalizeId(currentId);
        for (int index = 0; index < current.size(); index++) {
            if (current.get(index).id().equals(normalized)) {
                return current.get((index + 1) % current.size());
            }
        }
        return current.get(0);
    }

    /**
     * The currency an existing order was created in. Unknown ids get a
     * placeholder that renders the id and refuses every balance operation, so
     * such orders stay visible but cannot pay out until the currency is
     * configured again.
     */
    public OrderCurrency resolve(String id) {
        String normalized = OrderCurrency.normalizeId(id);
        if (normalized == null) {
            normalized = VAULT_ID;
        }
        OrderCurrency currency = byId.get(normalized);
        if (currency != null) {
            return currency;
        }
        return placeholders.computeIfAbsent(normalized, missingId -> new OrderCurrency(
            missingId,
            capitalize(missingId),
            OrderCurrency.AMOUNT_PLACEHOLDER + " " + capitalize(missingId),
            Material.BARRIER,
            true,
            -1D,
            CurrencyProvider.unavailable(),
            false,
            themeColor
        ));
    }

    private static String normalizeProvider(String rawProvider) {
        String normalized = rawProvider == null ? "" : rawProvider.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (normalized) {
            case "ee", "excellenteco" -> PROVIDER_EXCELLENT_ECONOMY;
            case "" -> PROVIDER_VAULT;
            default -> normalized;
        };
    }

    private static double parseTaxPercentage(ConfigurationSection entry, String id, Logger logger, FoFileLogger fileLogger) {
        if (!entry.isSet("tax-percentage")) {
            return -1D;
        }
        double configured = entry.getDouble("tax-percentage", -1D);
        if (!Double.isFinite(configured) || configured < 0D) {
            return -1D;
        }
        if (configured > MAX_TAX_PERCENTAGE) {
            warn(logger, fileLogger, "currencies." + id + ".tax-percentage is out of range; using " + MAX_TAX_PERCENTAGE + " (allowed 0-100).");
            return MAX_TAX_PERCENTAGE;
        }
        return configured;
    }

    private static Material parseMaterial(String configured, Logger logger, FoFileLogger fileLogger, String id) {
        if (configured == null || configured.isBlank()) {
            return Material.GOLD_INGOT;
        }
        Material material = Material.matchMaterial(configured.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            material = Material.matchMaterial(configured.trim().toLowerCase(Locale.ROOT));
        }
        if (material == null || !material.isItem()) {
            warn(logger, fileLogger, "Ignoring invalid icon '" + configured + "' for currency '" + id + "'.");
            return Material.GOLD_INGOT;
        }
        return material;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void warn(Logger logger, FoFileLogger fileLogger, String message) {
        if (logger != null) {
            logger.warning(message);
        }
        if (fileLogger != null) {
            fileLogger.warn(message);
        }
    }

    private static void info(Logger logger, FoFileLogger fileLogger, String message) {
        if (logger != null) {
            logger.info(message);
        }
        if (fileLogger != null) {
            fileLogger.info(message);
        }
    }
}
