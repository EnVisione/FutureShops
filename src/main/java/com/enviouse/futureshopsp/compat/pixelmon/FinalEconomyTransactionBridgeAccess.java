package com.enviouse.futureshopsp.compat.pixelmon;

import com.enviouse.futureshopsp.api.economy.MutationKind;
import com.enviouse.futureshopsp.api.economy.MutationReceipt;
import com.enviouse.futureshopsp.api.economy.ProviderError;
import com.enviouse.futureshopsp.api.economy.ProviderResult;
import com.enviouse.futureshopsp.api.economy.ProviderResultStatus;
import com.enviouse.futureshopsp.api.economy.RequestId;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

final class FinalEconomyTransactionBridgeAccess {
    static final String ACCOUNT_CLASS =
            "br.com.finalcraft.pixelmoneconomybridge.compat.v1_21_R1.reforged.finaleconomy.FEBankAccount";
    private static final String PLUGIN_NAME = "FutureShopsFinalEconomyBridge";
    private static final String SERVICE_CLASS_NAME =
            "com.enviouse.futureshopsbridge.FutureShopsFinalEconomyBridgeService";

    private final Object service;
    private final Method balance;
    private final Method precheck;
    private final Method mutate;
    private final Method lookup;

    private FinalEconomyTransactionBridgeAccess(Object service, Method balance, Method precheck, Method mutate,
                                                Method lookup) {
        this.service = service;
        this.balance = balance;
        this.precheck = precheck;
        this.mutate = mutate;
        this.lookup = lookup;
    }

    static FinalEconomyTransactionBridgeAccess discover(Object account) {
        if (account == null || !ACCOUNT_CLASS.equals(account.getClass().getName())) {
            return null;
        }
        try {
            ClassLoader accountLoader = account.getClass().getClassLoader();
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit", false, accountLoader);
            Object pluginManager = bukkit.getMethod("getPluginManager").invoke(null);
            Object plugin = pluginManager.getClass().getMethod("getPlugin", String.class)
                    .invoke(pluginManager, PLUGIN_NAME);
            if (plugin == null) {
                return null;
            }
            ClassLoader bridgeLoader = plugin.getClass().getClassLoader();
            Class<?> serviceType = Class.forName(SERVICE_CLASS_NAME, false, bridgeLoader);
            Object servicesManager = bukkit.getMethod("getServicesManager").invoke(null);
            Object registration = servicesManager.getClass().getMethod("getRegistration", Class.class)
                    .invoke(servicesManager, serviceType);
            if (registration == null) {
                return null;
            }
            Object provider = registration.getClass().getMethod("getProvider").invoke(registration);
            if (provider == null || !serviceType.isInstance(provider)) {
                return null;
            }
            return new FinalEconomyTransactionBridgeAccess(provider,
                    serviceType.getMethod("balance", Object.class, UUID.class),
                    serviceType.getMethod("precheck", Object.class, UUID.class, long.class, String.class),
                    serviceType.getMethod("mutate", Object.class, UUID.class, UUID.class, long.class,
                            String.class),
                    serviceType.getMethod("lookup", Object.class, UUID.class, UUID.class, long.class,
                            String.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    ProviderResult<BigDecimal> balance(Object account, UUID actor) {
        return parseBalance(invoke(balance, account, actor));
    }

    ProviderResult<BigDecimal> precheck(Object account, UUID actor, long amount, MutationKind kind) {
        return parseBalanceResult(invoke(precheck, account, actor, amount, kind.name()));
    }

    ProviderResult<MutationReceipt> mutate(Object account, UUID actor, RequestId requestId, long amount,
                                            MutationKind kind) {
        return parseReceiptResult(invoke(mutate, account, actor, requestId.value(), amount, kind.name()), requestId,
                amount, kind);
    }

    ProviderResult<MutationReceipt> lookup(Object account, UUID actor, RequestId requestId, long amount,
                                           MutationKind kind) {
        return parseReceiptResult(invoke(lookup, account, actor, requestId.value(), amount, kind.name()), requestId,
                amount, kind);
    }

    private Object invoke(Method method, Object... arguments) {
        try {
            return method.invoke(service, arguments);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
            return null;
        }
    }

    private static ProviderResult<BigDecimal> parseBalance(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                    "finaleconomy bridge did not return a balance result");
        }
        ProviderResultStatus status = status(map);
        if (status != ProviderResultStatus.CONFIRMED) {
            return nonConfirmed(status, error(map), diagnostic(map));
        }
        Object balance = map.get("balance");
        if (!(balance instanceof Number number)) {
            return ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                    "finaleconomy bridge balance was not numeric");
        }
        try {
            return ProviderResult.confirmed(exactDecimal(number));
        } catch (RuntimeException exception) {
            return ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                    "finaleconomy bridge balance was not exact");
        }
    }

    private static ProviderResult<BigDecimal> parseBalanceResult(Object value) {
        return parseBalance(value);
    }

    private static ProviderResult<MutationReceipt> parseReceiptResult(Object value, RequestId fallbackRequest,
                                                                        long fallbackAmount,
                                                                        MutationKind fallbackKind) {
        if (!(value instanceof Map<?, ?> map)) {
            return ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                    "finaleconomy bridge did not return a receipt result");
        }
        ProviderResultStatus status = status(map);
        if (status != ProviderResultStatus.CONFIRMED) {
            return nonConfirmed(status, error(map), diagnostic(map));
        }
        try {
            RequestId requestId = request(map, fallbackRequest);
            MutationKind kind = kind(map, fallbackKind);
            long amount = number(map.get("amount"), fallbackAmount);
            String externalId = text(map.get("external_operation_id"), "finaleconomy:" + requestId.value());
            OptionalLong resultingBalance = map.get("balance") instanceof Number number
                    ? OptionalLong.of(exactDecimal(number).longValueExact()) : OptionalLong.empty();
            return ProviderResult.confirmed(new MutationReceipt(requestId, kind, amount, externalId,
                    resultingBalance));
        } catch (RuntimeException exception) {
            return ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                    "finaleconomy bridge receipt was invalid");
        }
    }

    private static ProviderResultStatus status(Map<?, ?> map) {
        Object value = map.get("status");
        if (!(value instanceof String text)) {
            return ProviderResultStatus.UNAVAILABLE;
        }
        try {
            return ProviderResultStatus.valueOf(text);
        } catch (IllegalArgumentException exception) {
            return ProviderResultStatus.UNAVAILABLE;
        }
    }

    private static ProviderError error(Map<?, ?> map) {
        Object value = map.get("error");
        if (!(value instanceof String text)) {
            return ProviderError.PROVIDER_EXCEPTION;
        }
        try {
            return ProviderError.valueOf(text);
        } catch (IllegalArgumentException exception) {
            return ProviderError.PROVIDER_EXCEPTION;
        }
    }

    private static String diagnostic(Map<?, ?> map) {
        return text(map.get("diagnostic"), "finaleconomy bridge refused the request");
    }

    private static <T> ProviderResult<T> nonConfirmed(ProviderResultStatus status, ProviderError error,
                                                       String diagnostic) {
        if (status == ProviderResultStatus.CONFIRMED) {
            return ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION, "bridge result was inconsistent");
        }
        return switch (status) {
            case REJECTED -> ProviderResult.rejected(error, diagnostic);
            case AMBIGUOUS -> ProviderResult.ambiguous(diagnostic);
            case RECOVERY_REQUIRED -> ProviderResult.recoveryRequired(diagnostic);
            default -> ProviderResult.unavailable(error, diagnostic);
        };
    }

    private static RequestId request(Map<?, ?> map, RequestId fallback) {
        Object value = map.get("request_id");
        return value instanceof String text ? new RequestId(UUID.fromString(text)) : fallback;
    }

    private static MutationKind kind(Map<?, ?> map, MutationKind fallback) {
        Object value = map.get("kind");
        return value instanceof String text ? MutationKind.valueOf(text) : fallback;
    }

    private static long number(Object value, long fallback) {
        return value == null ? fallback : exactDecimal(value).longValueExact();
    }

    private static BigDecimal exactDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        throw new IllegalArgumentException("value is not numeric");
    }

    private static String text(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }
}
