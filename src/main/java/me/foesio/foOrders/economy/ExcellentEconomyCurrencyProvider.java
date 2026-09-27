package me.foesio.foOrders.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Currency backed by one ExcellentEconomy currency (for example "coins").
 *
 * <p>ExcellentEconomy is an optional dependency, so its API is reached through
 * reflection: the plugin stays loadable when ExcellentEconomy is absent and the
 * build does not need the ExcellentEconomy artifact. The API surface used here
 * matches what AxAuctions calls: {@code ExcellentEconomyAPI} is fetched from the
 * Bukkit services manager, {@code getCurrency(String)} resolves the currency,
 * balances are read with {@code getBalance(Player, currency)} /
 * {@code getBalanceAsync(UUID, currency)} and money moves through
 * {@code deposit}/{@code withdraw} (online players) or their {@code *Async}
 * variants (offline refunds), whose {@code OperationResult.success()} decides
 * the outcome.
 */
public final class ExcellentEconomyCurrencyProvider implements CurrencyProvider {
    public static final String PLUGIN_NAME = "ExcellentEconomy";
    private static final String API_CLASS_NAME = "su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI";
    private static final long ASYNC_TIMEOUT_SECONDS = 5L;
    private static final double BALANCE_EPSILON = 1.0E-6D;
    private static final List<String> BALANCE_NAMES = List.of("getBalance");
    private static final List<String> BALANCE_ASYNC_NAMES = List.of("getBalanceAsync");
    private static final List<String> DEPOSIT_NAMES = List.of("deposit", "addBalance", "give", "add");
    private static final List<String> DEPOSIT_ASYNC_NAMES = List.of("depositAsync", "addBalanceAsync", "giveAsync", "addAsync");
    private static final List<String> WITHDRAW_NAMES = List.of("withdraw", "removeBalance", "take", "remove");
    private static final List<String> WITHDRAW_ASYNC_NAMES = List.of("withdrawAsync", "removeBalanceAsync", "takeAsync", "removeAsync");

    private final Logger logger;
    private final String currencyId;
    private final Object api;
    private final Method getCurrency;
    private final Method balanceSync;
    private final Method balanceAsync;
    private final Method depositSync;
    private final Method depositAsync;
    private final Method withdrawSync;
    private final Method withdrawAsync;
    private final boolean decimal;

    private ExcellentEconomyCurrencyProvider(
        Logger logger,
        String currencyId,
        Object api,
        Method getCurrency,
        Method balanceSync,
        Method balanceAsync,
        Method depositSync,
        Method depositAsync,
        Method withdrawSync,
        Method withdrawAsync,
        boolean decimal
    ) {
        this.logger = logger;
        this.currencyId = currencyId;
        this.api = api;
        this.getCurrency = getCurrency;
        this.balanceSync = balanceSync;
        this.balanceAsync = balanceAsync;
        this.depositSync = depositSync;
        this.depositAsync = depositAsync;
        this.withdrawSync = withdrawSync;
        this.withdrawAsync = withdrawAsync;
        this.decimal = decimal;
    }

    /**
     * Result of looking the currency up. Exactly one of {@link #provider()} and
     * {@link #failureReason()} is set. {@link #pluginMissing()} marks the quiet
     * case where ExcellentEconomy simply is not installed on this server.
     */
    public record Resolution(ExcellentEconomyCurrencyProvider provider, String failureReason, boolean pluginMissing) {
        static Resolution success(ExcellentEconomyCurrencyProvider provider) {
            return new Resolution(provider, null, false);
        }

        static Resolution failure(String reason) {
            return new Resolution(null, reason, false);
        }

        static Resolution missingPlugin(String reason) {
            return new Resolution(null, reason, true);
        }
    }

    public static Resolution resolve(String currencyId, Logger logger) {
        String normalizedId = currencyId == null ? "" : currencyId.trim();
        if (normalizedId.isEmpty()) {
            return Resolution.failure("no ExcellentEconomy currency-id configured");
        }

        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null) {
            return Resolution.missingPlugin(PLUGIN_NAME + " is not installed");
        }
        if (!plugin.isEnabled()) {
            return Resolution.missingPlugin(PLUGIN_NAME + " is not enabled");
        }

        Class<?> apiClass;
        try {
            apiClass = Class.forName(API_CLASS_NAME, true, plugin.getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError exception) {
            return Resolution.failure(PLUGIN_NAME + " API class " + API_CLASS_NAME + " was not found (unsupported version?)");
        }

        Object api;
        try {
            api = Bukkit.getServicesManager().load(apiClass);
        } catch (RuntimeException exception) {
            return Resolution.failure(PLUGIN_NAME + " API service lookup failed: " + exception.getMessage());
        }
        if (api == null) {
            return Resolution.failure(PLUGIN_NAME + " has not registered its API service yet");
        }

        Method getCurrency = findMethod(apiClass, List.of("getCurrency"), String.class);
        if (getCurrency == null) {
            return Resolution.failure(PLUGIN_NAME + " API has no getCurrency(String) method (unsupported version?)");
        }

        Object currency;
        try {
            currency = getCurrency.invoke(api, normalizedId);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return Resolution.failure("could not look up " + PLUGIN_NAME + " currency '" + normalizedId + "': " + unwrap(exception));
        }
        if (currency == null) {
            return Resolution.failure("currency '" + normalizedId + "' does not exist in " + PLUGIN_NAME + " (check ExcellentEconomy/currencies/)");
        }

        Class<?> currencyClass = currency.getClass();
        Method balanceSync = findMethod(apiClass, BALANCE_NAMES, Player.class, currencyClass);
        Method balanceAsync = findMethod(apiClass, BALANCE_ASYNC_NAMES, UUID.class, currencyClass);
        Method depositSync = findMethod(apiClass, DEPOSIT_NAMES, Player.class, currencyClass, double.class);
        Method depositAsync = findMethod(apiClass, DEPOSIT_ASYNC_NAMES, UUID.class, currencyClass, double.class);
        Method withdrawSync = findMethod(apiClass, WITHDRAW_NAMES, Player.class, currencyClass, double.class);
        Method withdrawAsync = findMethod(apiClass, WITHDRAW_ASYNC_NAMES, UUID.class, currencyClass, double.class);
        if (balanceSync == null && balanceAsync == null) {
            return Resolution.failure(PLUGIN_NAME + " API has no balance lookup method (unsupported version?)");
        }
        if (depositSync == null && depositAsync == null) {
            return Resolution.failure(PLUGIN_NAME + " API has no deposit method (unsupported version?)");
        }
        if (withdrawSync == null && withdrawAsync == null) {
            return Resolution.failure(PLUGIN_NAME + " API has no withdraw method (unsupported version?)");
        }

        boolean decimal = readDecimalFlag(currency, logger);
        return Resolution.success(new ExcellentEconomyCurrencyProvider(
            logger,
            normalizedId,
            api,
            getCurrency,
            balanceSync,
            balanceAsync,
            depositSync,
            depositAsync,
            withdrawSync,
            withdrawAsync,
            decimal
        ));
    }

    public String currencyId() {
        return currencyId;
    }

    @Override
    public boolean isAvailable() {
        return liveCurrency() != null;
    }

    /**
     * Looks the currency up on every operation instead of caching the object, so an
     * ExcellentEconomy reload that re-creates or removes the currency is picked up.
     * Returns null when ExcellentEconomy is disabled or the currency no longer exists.
     */
    private Object liveCurrency() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        try {
            return getCurrency.invoke(api, currencyId);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    @Override
    public boolean supportsDecimals() {
        return decimal;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        Double balance = balance(player);
        return balance != null && balance + BALANCE_EPSILON >= amount;
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        Object currency = liveCurrency();
        if (player == null || currency == null || !(amount > 0D) || !Double.isFinite(amount)) {
            return false;
        }
        Player online = player.getPlayer();
        try {
            if (online != null && withdrawSync != null) {
                return interpretResult(withdrawSync.invoke(api, online, currency, amount));
            }
            if (withdrawAsync != null) {
                return interpretResult(await(withdrawAsync.invoke(api, player.getUniqueId(), currency, amount)));
            }
        } catch (TimeoutException exception) {
            warn("Timed out withdrawing " + amount + " " + currencyId + " from " + describe(player) + "; treating as failed.");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warn("Could not withdraw " + amount + " " + currencyId + " from " + describe(player) + ": " + unwrap(exception));
        }
        return false;
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        Object currency = liveCurrency();
        if (player == null || currency == null || !(amount > 0D) || !Double.isFinite(amount)) {
            return false;
        }
        Player online = player.getPlayer();
        try {
            if (online != null && depositSync != null) {
                return interpretResult(depositSync.invoke(api, online, currency, amount));
            }
            if (depositAsync != null) {
                return interpretResult(await(depositAsync.invoke(api, player.getUniqueId(), currency, amount)));
            }
        } catch (TimeoutException exception) {
            // The deposit is still in flight inside ExcellentEconomy. Reporting a failure here
            // would make the caller retry and could pay the player twice, so accept it.
            warn("Timed out waiting for a deposit of " + amount + " " + currencyId + " to " + describe(player) + "; assuming it completes.");
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warn("Could not deposit " + amount + " " + currencyId + " to " + describe(player) + ": " + unwrap(exception));
        }
        return false;
    }

    private Double balance(OfflinePlayer player) {
        Object currency = liveCurrency();
        if (player == null || currency == null) {
            return null;
        }
        Player online = player.getPlayer();
        try {
            Object result = null;
            if (online != null && balanceSync != null) {
                result = balanceSync.invoke(api, online, currency);
            } else if (balanceAsync != null) {
                result = await(balanceAsync.invoke(api, player.getUniqueId(), currency));
            }
            return result instanceof Number number ? number.doubleValue() : null;
        } catch (TimeoutException exception) {
            warn("Timed out reading the " + currencyId + " balance of " + describe(player) + ".");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warn("Could not read the " + currencyId + " balance of " + describe(player) + ": " + unwrap(exception));
        }
        return null;
    }

    private static Object await(Object result) throws TimeoutException {
        Future<?> future;
        if (result instanceof CompletionStage<?> stage) {
            future = stage.toCompletableFuture();
        } else if (result instanceof Future<?> plainFuture) {
            future = plainFuture;
        } else {
            return result;
        }
        try {
            return future.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + PLUGIN_NAME, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    /**
     * Accepts booleans, void results and ExcellentEconomy's {@code OperationResult}
     * (checked through its {@code success()} accessor).
     */
    private static boolean interpretResult(Object result) {
        if (result == null) {
            return true;
        }
        if (result instanceof Boolean success) {
            return success;
        }
        for (String accessor : List.of("success", "isSuccess", "isSuccessful")) {
            try {
                Method method = result.getClass().getMethod(accessor);
                method.setAccessible(true);
                Object value = method.invoke(result);
                if (value instanceof Boolean success) {
                    return success;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next accessor name.
            }
        }
        return true;
    }

    private static boolean readDecimalFlag(Object currency, Logger logger) {
        for (String accessor : List.of("isDecimal", "usesDecimals", "isDecimalEnabled")) {
            try {
                Method method = currency.getClass().getMethod(accessor);
                method.setAccessible(true);
                Object value = method.invoke(currency);
                if (value instanceof Boolean decimal) {
                    return decimal;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next accessor name.
            }
        }
        if (logger != null) {
            logger.info(PLUGIN_NAME + " currency does not expose a decimal flag; assuming decimals are allowed.");
        }
        return true;
    }

    private static Method findMethod(Class<?> type, List<String> names, Class<?>... parameterTypes) {
        for (Method method : type.getMethods()) {
            if (!names.contains(method.getName()) || method.getParameterCount() != parameterTypes.length) {
                continue;
            }
            Class<?>[] actual = method.getParameterTypes();
            boolean matches = true;
            for (int index = 0; index < parameterTypes.length; index++) {
                if (!actual[index].isAssignableFrom(parameterTypes[index])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static String unwrap(Exception exception) {
        Throwable cause = exception instanceof InvocationTargetException invocation && invocation.getCause() != null
            ? invocation.getCause()
            : exception;
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static String describe(OfflinePlayer player) {
        String name = player.getName();
        return name == null || name.isBlank() ? String.valueOf(player.getUniqueId()) : name;
    }

    private void warn(String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }
}
