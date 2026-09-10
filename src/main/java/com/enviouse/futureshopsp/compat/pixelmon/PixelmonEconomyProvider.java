package com.enviouse.futureshopsp.compat.pixelmon;

import com.enviouse.futureshopsp.api.economy.BalanceSnapshot;
import com.enviouse.futureshopsp.api.economy.CurrencyMetadata;
import com.enviouse.futureshopsp.api.economy.EconomyApi;
import com.enviouse.futureshopsp.api.economy.MutationKind;
import com.enviouse.futureshopsp.api.economy.MutationReceipt;
import com.enviouse.futureshopsp.api.economy.MutationRequest;
import com.enviouse.futureshopsp.api.economy.ProviderCapabilities;
import com.enviouse.futureshopsp.api.economy.ProviderError;
import com.enviouse.futureshopsp.api.economy.ProviderLifecycle;
import com.enviouse.futureshopsp.api.economy.ProviderReadiness;
import com.enviouse.futureshopsp.api.economy.ProviderResult;
import com.enviouse.futureshopsp.api.economy.RequestId;
import com.enviouse.futureshopsp.server.debug.DebugDiagnostics;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** Optional Pixelmon adapter. */
public final class PixelmonEconomyProvider implements com.enviouse.futureshopsp.api.economy.EconomyProvider {
    public static final String PROVIDER_ID = EconomyApi.PIXELMON_PROVIDER_ID;
    public static final String SUPPORTED_VERSION = "9.4.0";
    private static final String PROXY_CLASS = "com.pixelmonmod.pixelmon.api.economy.BankAccountProxy";
    private static final String ACCOUNT_CLASS = "com.pixelmonmod.pixelmon.api.economy.BankAccount";
    private static final CurrencyMetadata CURRENCY = new CurrencyMetadata("PokéDollar", "PokéDollars", 0);
    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(true, true, false, false,
            false, false);

    private final RuntimeAccess runtime;
    private final MinecraftServer server;

    public PixelmonEconomyProvider() {
        this(null);
    }

    public PixelmonEconomyProvider(MinecraftServer server) {
        this.server = server;
        this.runtime = RuntimeAccess.load();
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public int compatibilityVersion() {
        return EconomyApi.COMPATIBILITY_VERSION;
    }

    @Override
    public CurrencyMetadata currency() {
        return CURRENCY;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return runtime.nativeMixinAvailable()
                ? new ProviderCapabilities(true, true, true, true, true, true)
                : CAPABILITIES;
    }

    @Override
    public ProviderReadiness readiness() {
        if (!runtime.available()) {
            ProviderReadiness result = new ProviderReadiness(ProviderLifecycle.FAILED, runtime.diagnostic());
            DebugDiagnostics.provider(PROVIDER_ID, "readiness", null, null, "unknown", null, capabilities(),
                    ProviderCapabilities.none(), "keep provider unavailable");
            return result;
        }
        try {
            if (!runtime.hasImplementation()) {
                ProviderReadiness result = new ProviderReadiness(ProviderLifecycle.MISSING,
                        "pixelmon economy implementation is unavailable");
                DebugDiagnostics.provider(PROVIDER_ID, "readiness", null, null, "unknown", null, capabilities(),
                        ProviderCapabilities.none(), "wait for Pixelmon readiness");
                return result;
            }
            ProviderReadiness result = new ProviderReadiness(ProviderLifecycle.READY, "");
            DebugDiagnostics.provider(PROVIDER_ID, "readiness", null, null, "unknown", null, capabilities(),
                    ProviderCapabilities.none(), "query or precheck before mutation");
            return result;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            ProviderReadiness result = new ProviderReadiness(ProviderLifecycle.FAILED,
                    "pixelmon economy readiness check failed");
            DebugDiagnostics.provider(PROVIDER_ID, "readiness", null, null, "unknown", null, capabilities(),
                    ProviderCapabilities.none(), "keep provider unavailable and inspect the sanitized failure");
            return result;
        }
    }

    @Override
    public ProviderResult<BalanceSnapshot> balance(UUID playerId) {
        if (playerId == null) {
            return ProviderResult.rejected(ProviderError.INVALID_REQUEST, "player id is required");
        }
        AccountRead account = readAccount(playerId);
        if (!account.available()) {
            ProviderResult<BalanceSnapshot> result = ProviderResult.unavailable(account.error(), account.diagnostic());
            DebugDiagnostics.provider(PROVIDER_ID, "balance", playerId, result, account.classification(), null,
                    capabilities(), observedCapabilities(account), "retry after readiness is restored");
            return result;
        }
        try {
            if (account.bridge() != null) {
                ProviderResult<BigDecimal> bridgeBalance = account.bridge().balance(account.value(), playerId);
                if (!bridgeBalance.confirmed()) {
                    ProviderResult<BalanceSnapshot> result = ProviderResult.unavailable(bridgeBalance.error(),
                            bridgeBalance.diagnostic());
                    DebugDiagnostics.provider(PROVIDER_ID, "balance", playerId, result, account.classification(),
                            null, capabilities(), observedCapabilities(account),
                            "keep the exact hybrid account unavailable");
                    return result;
                }
                ProviderResult<BalanceSnapshot> result = ProviderResult.confirmed(new BalanceSnapshot(playerId,
                        toMinorUnits(bridgeBalance.value().orElseThrow())));
                DebugDiagnostics.provider(PROVIDER_ID, "balance", playerId, result, account.classification(), null,
                        capabilities(), observedCapabilities(account), "balance query has no side effect");
                return result;
            }
            ProviderResult<BalanceSnapshot> result = ProviderResult.confirmed(
                    new BalanceSnapshot(playerId, toMinorUnits(account.balance())));
            DebugDiagnostics.provider(PROVIDER_ID, "balance", playerId, result, account.classification(), null,
                    capabilities(), observedCapabilities(account), "balance query has no side effect");
            return result;
        } catch (ArithmeticException | IllegalArgumentException exception) {
            ProviderResult<BalanceSnapshot> result = ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                    "pixelmon balance is not an exact integer amount");
            DebugDiagnostics.provider(PROVIDER_ID, "balance", playerId, result, account.classification(), null,
                    capabilities(), observedCapabilities(account), "keep the account unavailable until exact conversion is proven");
            return result;
        }
    }

    @Override
    public ProviderResult<BalanceSnapshot> precheck(MutationRequest request) {
        if (request == null) {
            return ProviderResult.rejected(ProviderError.INVALID_REQUEST, "mutation request is required");
        }
        AccountRead account = readAccount(request.actor());
        if (!account.available()) {
            ProviderResult<BalanceSnapshot> result = ProviderResult.unavailable(account.error(), account.diagnostic());
            DebugDiagnostics.provider(PROVIDER_ID, "precheck", request.actor(), result, account.classification(),
                    capabilities(), capabilities(), observedCapabilities(account), "do not append intent");
            return result;
        }
        if (runtime.nativeMixinAvailable() && !(account.value() instanceof PixelmonNativeEconomyAccess)
                && account.bridge() == null) {
            ProviderResult<BalanceSnapshot> result = ProviderResult.rejected(ProviderError.CAPABILITY_MISSING,
                    "pixelmon account is not the native receipt account");
            DebugDiagnostics.provider(PROVIDER_ID, "precheck", request.actor(), result, account.classification(),
                    capabilities(), capabilities(), observedCapabilities(account), "leave journal and custody unchanged");
            return result;
        }
        if (account.bridge() != null) {
            ProviderResult<BigDecimal> bridgePrecheck = account.bridge().precheck(account.value(), request.actor(),
                    request.amountMinorUnits(), request.kind());
            if (!bridgePrecheck.confirmed()) {
                ProviderResult<BalanceSnapshot> result = nonConfirmedBalance(bridgePrecheck);
                DebugDiagnostics.provider(PROVIDER_ID, "precheck", request.actor(), result, account.classification(),
                        capabilities(), capabilities(), observedCapabilities(account),
                        "leave journal and custody unchanged");
                return result;
            }
            try {
                ProviderResult<BalanceSnapshot> result = ProviderResult.confirmed(new BalanceSnapshot(request.actor(),
                        toMinorUnits(bridgePrecheck.value().orElseThrow())));
                DebugDiagnostics.provider(PROVIDER_ID, "precheck", request.actor(), result, account.classification(),
                        capabilities(), capabilities(), observedCapabilities(account),
                        "continue through the durable coordinator");
                return result;
            } catch (ArithmeticException | IllegalArgumentException exception) {
                ProviderResult<BalanceSnapshot> result = ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                        "finaleconomy balance is not an exact integer amount");
                DebugDiagnostics.provider(PROVIDER_ID, "precheck", request.actor(), result, account.classification(),
                        capabilities(), capabilities(), observedCapabilities(account), "keep the mutation refused");
                return result;
            }
        }
        long balance;
        try {
            balance = toMinorUnits(account.balance());
        } catch (ArithmeticException | IllegalArgumentException exception) {
            ProviderResult<BalanceSnapshot> result = ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                    "pixelmon balance is not an exact integer amount");
            DebugDiagnostics.provider(PROVIDER_ID, "precheck", request.actor(), result, account.classification(),
                    capabilities(), capabilities(), observedCapabilities(account), "keep the mutation refused");
            return result;
        }
        if (requiresFunds(request.kind())) {
            try {
                if (!runtime.hasBalance(account.value(), BigDecimal.valueOf(request.amountMinorUnits()))) {
                    ProviderResult<BalanceSnapshot> result = ProviderResult.rejected(ProviderError.INSUFFICIENT_FUNDS,
                            "insufficient PokéDollars");
                    DebugDiagnostics.provider(PROVIDER_ID, "precheck", request.actor(), result, account.classification(),
                            capabilities(), capabilities(), observedCapabilities(account), "leave journal and custody unchanged");
                    return result;
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                ProviderResult<BalanceSnapshot> result = ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                        "pixelmon funds check failed");
                DebugDiagnostics.provider(PROVIDER_ID, "precheck", request.actor(), result, account.classification(),
                        capabilities(), capabilities(), observedCapabilities(account), "keep the mutation refused");
                return result;
            }
        }
        ProviderResult<BalanceSnapshot> result = ProviderResult.confirmed(new BalanceSnapshot(request.actor(), balance));
        DebugDiagnostics.provider(PROVIDER_ID, "precheck", request.actor(), result, account.classification(),
                capabilities(), capabilities(), observedCapabilities(account), "continue through the durable coordinator");
        return result;
    }

    @Override
    public ProviderResult<MutationReceipt> withdraw(MutationRequest request) {
        return mutate(request, MutationKind.WITHDRAW);
    }

    @Override
    public ProviderResult<MutationReceipt> deposit(MutationRequest request) {
        return mutate(request, MutationKind.DEPOSIT);
    }

    @Override
    public ProviderResult<MutationReceipt> lookup(RequestId requestId) {
        if (requestId == null) {
            return ProviderResult.rejected(ProviderError.INVALID_REQUEST, "receipt request is required");
        }
        return ProviderResult.rejected(ProviderError.CAPABILITY_MISSING,
                "pixelmon receipt lookup requires the persisted account binding");
    }

    @Override
    public ProviderResult<MutationReceipt> lookup(MutationRequest request) {
        if (request == null || server == null) {
            return ProviderResult.rejected(ProviderError.CAPABILITY_MISSING,
                    "pixelmon native receipt lookup is unavailable");
        }
        try {
            Object account = runtime.account(request.actor());
            FinalEconomyTransactionBridgeAccess bridge = FinalEconomyTransactionBridgeAccess.discover(account);
            if (bridge != null) {
                return bridge.lookup(account, request.actor(), request.requestId(), request.amountMinorUnits(),
                        request.kind());
            }
            if (!(account instanceof PixelmonNativeEconomyAccess nativeAccount)) {
                return ProviderResult.rejected(ProviderError.CAPABILITY_MISSING,
                        "pixelmon account is not a native receipt account");
            }
            return nativeAccount.futureshopsLookup(request.requestId());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return ProviderResult.unavailable(ProviderError.PROVIDER_EXCEPTION,
                    "pixelmon receipt lookup failed");
        }
    }

    @Override
    public ProviderResult<MutationReceipt> retry(MutationRequest request) {
        return mutate(request, request == null ? MutationKind.WITHDRAW : mutationRoute(request.kind()));
    }

    private ProviderResult<MutationReceipt> mutate(MutationRequest request, MutationKind expectedKind) {
        if (request == null) {
            return ProviderResult.rejected(ProviderError.INVALID_REQUEST, "mutation request is invalid");
        }
        if (!matchesMutationRoute(request.kind(), expectedKind)) {
            return mutationRefused();
        }
        AccountRead account = readAccount(request.actor());
        if (!account.available()) {
            return ProviderResult.unavailable(account.error(), account.diagnostic());
        }
        if (!(account.value() instanceof PixelmonNativeEconomyAccess nativeAccount)) {
            if (account.bridge() != null) {
                ProviderResult<MutationReceipt> result = account.bridge().mutate(account.value(), request.actor(),
                        request.requestId(), request.amountMinorUnits(), request.kind());
                DebugDiagnostics.provider(PROVIDER_ID, "mutation", request.actor(), result, account.classification(),
                        capabilities(), capabilities(), observedCapabilities(account), result.confirmed()
                                ? "finalize the coordinator record" : "follow the typed result and do not guess");
                return result;
            }
            ProviderResult<MutationReceipt> result = mutationRefused();
            DebugDiagnostics.provider(PROVIDER_ID, "mutation", request.actor(), result, account.classification(),
                    capabilities(), capabilities(), observedCapabilities(account), "keep the surface refused before intent or custody");
            return result;
        }
        if (server == null) {
            ProviderResult<MutationReceipt> result = ProviderResult.unavailable(ProviderError.NOT_READY,
                    "pixelmon mutation requires a live server context");
            DebugDiagnostics.provider(PROVIDER_ID, "mutation", request.actor(), result, account.classification(),
                    capabilities(), capabilities(), observedCapabilities(account), "run only on a live server");
            return result;
        }
        ProviderResult<MutationReceipt> result = nativeAccount.futureshopsMutate(request.requestId(), request.kind(),
                request.amountMinorUnits(), server.registryAccess());
        DebugDiagnostics.provider(PROVIDER_ID, "mutation", request.actor(), result, account.classification(),
                capabilities(), capabilities(), observedCapabilities(account), result.confirmed() ? "finalize the coordinator record"
                        : "follow the typed result and do not guess");
        return result;
    }

    private static boolean requiresFunds(MutationKind kind) {
        return kind == MutationKind.WITHDRAW || kind == MutationKind.TRANSFER_DEBIT
                || kind == MutationKind.FEE;
    }

    private static MutationKind mutationRoute(MutationKind kind) {
        return switch (kind) {
            case DEPOSIT, TRANSFER_CREDIT, REFUND, COMPENSATION -> MutationKind.DEPOSIT;
            default -> MutationKind.WITHDRAW;
        };
    }

    private static boolean matchesMutationRoute(MutationKind kind, MutationKind route) {
        return mutationRoute(kind) == route;
    }

    private static ProviderResult<MutationReceipt> mutationRefused() {
        return ProviderResult.rejected(ProviderError.CAPABILITY_MISSING,
                "pixelmon direct mutations require durable receipts");
    }

    private static ProviderResult<BalanceSnapshot> nonConfirmedBalance(ProviderResult<BigDecimal> result) {
        return switch (result.status()) {
            case REJECTED -> ProviderResult.rejected(result.error(), result.diagnostic());
            case AMBIGUOUS -> ProviderResult.ambiguous(result.diagnostic());
            case RECOVERY_REQUIRED -> ProviderResult.recoveryRequired(result.diagnostic());
            default -> ProviderResult.unavailable(result.error(), result.diagnostic());
        };
    }

    private static ProviderCapabilities observedCapabilities(AccountRead account) {
        if (account == null || !account.available()) {
            return ProviderCapabilities.none();
        }
        boolean nativeReceipt = account.value() instanceof PixelmonNativeEconomyAccess;
        boolean transactionBridge = account.bridge() != null;
        boolean mutation = nativeReceipt || transactionBridge;
        return new ProviderCapabilities(true, true, mutation, mutation, mutation, mutation);
    }

    private AccountRead readAccount(UUID playerId) {
        if (!runtime.available()) {
            return AccountRead.unavailable(ProviderError.PROVIDER_EXCEPTION, runtime.diagnostic());
        }
        try {
            if (!runtime.hasImplementation()) {
                return AccountRead.unavailable(ProviderError.NOT_READY,
                        "pixelmon economy implementation is unavailable");
            }
            Object account = runtime.account(playerId);
            if (account == null) {
                return AccountRead.unavailable(ProviderError.PROVIDER_EXCEPTION,
                        "pixelmon account is unavailable");
            }
            UUID identifier = runtime.identifier(account);
            if (!playerId.equals(identifier)) {
                return AccountRead.unavailable(ProviderError.PROVIDER_EXCEPTION,
                        "pixelmon account identity did not match player");
            }
            FinalEconomyTransactionBridgeAccess bridge = FinalEconomyTransactionBridgeAccess.discover(account);
            BigDecimal balance;
            if (bridge != null) {
                ProviderResult<BigDecimal> bridgeBalance = bridge.balance(account, playerId);
                if (!bridgeBalance.confirmed()) {
                    return AccountRead.unavailable(bridgeBalance.error(), bridgeBalance.diagnostic());
                }
                balance = bridgeBalance.value().orElseThrow();
            } else {
                balance = runtime.balance(account);
            }
            if (balance == null) {
                return AccountRead.unavailable(ProviderError.PROVIDER_EXCEPTION,
                        "pixelmon balance is unavailable");
            }
            return AccountRead.available(account, balance, bridge);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return AccountRead.unavailable(ProviderError.PROVIDER_EXCEPTION,
                    "pixelmon economy query failed");
        }
    }

    private static long toMinorUnits(BigDecimal balance) {
        return balance.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    private record AccountRead(Object value, BigDecimal balance, ProviderError error, String diagnostic,
                               FinalEconomyTransactionBridgeAccess bridge) {
        static AccountRead available(Object value, BigDecimal balance,
                                      FinalEconomyTransactionBridgeAccess bridge) {
            return new AccountRead(value, balance, ProviderError.NONE, "", bridge);
        }

        static AccountRead unavailable(ProviderError error, String diagnostic) {
            return new AccountRead(null, null, error, diagnostic, null);
        }

        boolean available() {
            return value != null && balance != null;
        }

        String classification() {
            return value == null ? "unavailable" : value instanceof PixelmonNativeEconomyAccess
                    ? "native_player_party_storage" : "custom_or_hybrid";
        }
    }

    private static final class RuntimeAccess {
        private final Method hasImplementation;
        private final Method getBankAccountNow;
        private final Method getIdentifier;
        private final Method getBalance;
        private final Method hasBalance;
        private final boolean nativeMixinAvailable;
        private final String diagnostic;

        private RuntimeAccess(Method hasImplementation, Method getBankAccountNow, Method getIdentifier,
                              Method getBalance, Method hasBalance, boolean nativeMixinAvailable,
                              String diagnostic) {
            this.hasImplementation = hasImplementation;
            this.getBankAccountNow = getBankAccountNow;
            this.getIdentifier = getIdentifier;
            this.getBalance = getBalance;
            this.hasBalance = hasBalance;
            this.nativeMixinAvailable = nativeMixinAvailable;
            this.diagnostic = diagnostic;
        }

        static RuntimeAccess load() {
            try {
                Class<?> proxy = Class.forName(PROXY_CLASS, false, PixelmonEconomyProvider.class.getClassLoader());
                Class<?> account = Class.forName(ACCOUNT_CLASS, false, PixelmonEconomyProvider.class.getClassLoader());
                boolean mixinAvailable = false;
                try {
                    Class<?> nativeAccess = Class.forName(
                            "com.enviouse.futureshopsp.compat.pixelmon.PixelmonNativeEconomyAccess", false,
                            PixelmonEconomyProvider.class.getClassLoader());
                    mixinAvailable = nativeAccess.isAssignableFrom(Class.forName(
                            "com.pixelmonmod.pixelmon.api.storage.PlayerPartyStorage", false,
                            PixelmonEconomyProvider.class.getClassLoader()));
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    // The optional native account target is absent in test and standard environments.
                }
                return new RuntimeAccess(
                        proxy.getMethod("hasImplementation"),
                        proxy.getMethod("getBankAccountNow", UUID.class),
                        account.getMethod("getIdentifier"),
                        account.getMethod("getBalance"),
                        account.getMethod("hasBalance", BigDecimal.class), mixinAvailable, "");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                return new RuntimeAccess(null, null, null, null, null, false,
                        "pixelmon 9.4.0 economy api is unavailable");
            }
        }

        boolean available() {
            return hasImplementation != null;
        }

        String diagnostic() {
            return diagnostic;
        }

        boolean nativeMixinAvailable() {
            return nativeMixinAvailable;
        }

        boolean hasImplementation() throws ReflectiveOperationException {
            return (Boolean) invokeStatic(hasImplementation);
        }

        Object account(UUID playerId) throws ReflectiveOperationException {
            return invoke(getBankAccountNow, null, playerId);
        }

        UUID identifier(Object account) throws ReflectiveOperationException {
            return (UUID) invoke(getIdentifier, account);
        }

        BigDecimal balance(Object account) throws ReflectiveOperationException {
            return (BigDecimal) invoke(getBalance, account);
        }

        boolean hasBalance(Object account, BigDecimal amount) throws ReflectiveOperationException {
            return (Boolean) invoke(hasBalance, account, amount);
        }

        private static Object invokeStatic(Method method, Object... arguments) throws ReflectiveOperationException {
            return invoke(method, null, arguments);
        }

        private static Object invoke(Method method, Object target, Object... arguments)
                throws ReflectiveOperationException {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof ReflectiveOperationException reflective) {
                    throw reflective;
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new ReflectiveOperationException("pixelmon economy call failed", cause);
            }
        }
    }
}
