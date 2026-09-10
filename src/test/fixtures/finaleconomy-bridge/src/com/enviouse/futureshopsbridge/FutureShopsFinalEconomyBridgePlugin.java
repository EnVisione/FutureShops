package com.enviouse.futureshopsbridge;

import br.com.finalcraft.evernifecore.config.Config;
import br.com.finalcraft.finaleconomy.config.data.FEPlayerData;
import br.com.finalcraft.evernifecore.config.playerdata.PlayerController;
import br.com.finalcraft.evernifecore.config.playerdata.PlayerData;
import br.com.finalcraft.evernifecore.util.numberwrapper.NumberWrapper;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.security.MessageDigest;

public final class FutureShopsFinalEconomyBridgePlugin extends JavaPlugin
        implements FutureShopsFinalEconomyBridgeService {
    private static final String RECEIPT_ROOT = "FutureShops.receipts.";
    private static final String RECEIPT_PROTOCOL = "stronger_exact_route_v1";
    private static final String CURRENCY_ID = "PokéDollar";
    private static final String BACKEND_LINEAGE_PREFIX = "EverNifeCore:PlayerData:";
    private static final ReentrantLock LEDGER_LOCK = new ReentrantLock(true);
    private static final Semaphore DISPATCH_SLOTS = new Semaphore(64, true);
    private static final Map<FEPlayerData, ReceiptAwareNumberWrapper> WRAPPERS = new ConcurrentHashMap<>();
    private static final Field ACCOUNT_PLAYER_DATA = field(
            "br.com.finalcraft.pixelmoneconomybridge.compat.v1_21_R1.reforged.finaleconomy.FEBankAccount",
            "playerData");
    private static final Field MONEY_WRAPPER = field(FEPlayerData.class, "moneyWrapper");
    private static final Field CONFIG_LOCK = field(Config.class, "lock");
    private static final ThreadLocal<TransactionContext> TRANSACTION = new ThreadLocal<>();
    private static final String CRASH_STAGE = System.getProperty("futureshops.bridge.crashStage", "").trim();
    private static final boolean TRACE = Boolean.getBoolean("futureshops.bridge.trace");

    @Override
    public void onEnable() {
        if (!exactDependenciesPresent()) {
            getLogger().severe("FutureShops FinalEconomy bridge requires the exact 1.21.1 dependency stack");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getServicesManager().register(FutureShopsFinalEconomyBridgeService.class, this, this,
                ServicePriority.Highest);
        prepareProbeAccount();
        getLogger().info("FutureShops FinalEconomy bridge registered for exact FEBankAccount receipts");
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregister(FutureShopsFinalEconomyBridgeService.class, this);
        WRAPPERS.clear();
    }

    @Override
    public Map<String, Object> balance(Object account, UUID actor) {
        if (account == null || actor == null) {
            return FutureShopsFinalEconomyBridgeService.rejected("INVALID_REQUEST", "account and actor are required");
        }
        LEDGER_LOCK.lock();
        try {
            FEPlayerData data = playerData(account, actor);
            install(data);
            return FutureShopsFinalEconomyBridgeService.confirmedBalance(exactMoney(data));
        } catch (RuntimeException exception) {
            return FutureShopsFinalEconomyBridgeService.unavailable("exact FinalEconomy balance is unavailable");
        } finally {
            LEDGER_LOCK.unlock();
        }
    }

    @Override
    public Map<String, Object> precheck(Object account, UUID actor, long amount, String kind) {
        if (account == null || actor == null || amount <= 0L || kind == null || kind.isBlank()) {
            return FutureShopsFinalEconomyBridgeService.rejected("INVALID_REQUEST", "request is invalid");
        }
        LEDGER_LOCK.lock();
        try {
            FEPlayerData data = playerData(account, actor);
            install(data);
            long balance = exactMoney(data);
            if (debit(kind) && balance < amount) {
                return FutureShopsFinalEconomyBridgeService.rejected("INSUFFICIENT_FUNDS",
                        "FinalEconomy balance is insufficient");
            }
            return FutureShopsFinalEconomyBridgeService.confirmedBalance(balance);
        } catch (RuntimeException exception) {
            return FutureShopsFinalEconomyBridgeService.unavailable("exact FinalEconomy precheck failed");
        } finally {
            LEDGER_LOCK.unlock();
        }
    }

    @Override
    public Map<String, Object> mutate(Object account, UUID actor, UUID requestId, long amount, String kind) {
        if (account == null || actor == null || requestId == null || amount <= 0L || kind == null || kind.isBlank()) {
            return FutureShopsFinalEconomyBridgeService.rejected("INVALID_REQUEST", "request is invalid");
        }
        if (!Bukkit.isPrimaryThread()) {
            if (!DISPATCH_SLOTS.tryAcquire()) {
                return FutureShopsFinalEconomyBridgeService.unavailable(
                        "FinalEconomy mutation dispatch queue is full");
            }
            try {
                Future<Map<String, Object>> future = Bukkit.getScheduler().callSyncMethod(this,
                        () -> mutateOnServerThread(account, actor, requestId, amount, kind));
                return future.get(5L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return FutureShopsFinalEconomyBridgeService.unavailable(
                        "FinalEconomy mutation dispatch was interrupted");
            } catch (TimeoutException exception) {
                return FutureShopsFinalEconomyBridgeService.ambiguous(
                        "FinalEconomy mutation dispatch did not finish");
            } catch (ExecutionException exception) {
                return FutureShopsFinalEconomyBridgeService.unavailable(
                        "FinalEconomy mutation dispatch failed");
            } finally {
                DISPATCH_SLOTS.release();
            }
        }
        return mutateOnServerThread(account, actor, requestId, amount, kind);
    }

    private Map<String, Object> mutateOnServerThread(Object account, UUID actor, UUID requestId, long amount,
                                                     String kind) {
        LEDGER_LOCK.lock();
        try {
            FEPlayerData data = playerData(account, actor);
            install(data);
            trace(requestId, "entered data=" + System.identityHashCode(data) + " balance=" + exactMoney(data));
            Receipt existing = readReceipt(data.getConfig(), requestId);
            if (existing != null) {
                trace(requestId, "existing receipt after=" + existing.after);
                return existing.compatible(actor, kind, amount)
                        ? FutureShopsFinalEconomyBridgeService.confirmedReceipt(requestId, actor, kind, amount,
                        existing.after)
                        : FutureShopsFinalEconomyBridgeService.rejected("REQUEST_CONFLICT",
                        "request id is already bound to another operation");
            }
            long before = exactMoney(data);
            trace(requestId, "before=" + before + " kind=" + kind + " amount=" + amount);
            long after;
            try {
                after = debit(kind) ? Math.subtractExact(before, amount) : Math.addExact(before, amount);
            } catch (ArithmeticException exception) {
                return FutureShopsFinalEconomyBridgeService.rejected("INVALID_AMOUNT", "balance arithmetic overflow");
            }
            if (after < 0L) {
                return FutureShopsFinalEconomyBridgeService.rejected("INSUFFICIENT_FUNDS",
                        "FinalEconomy balance is insufficient");
            }
            TransactionContext context = new TransactionContext(data, actor, requestId, kind, amount, before, after);
            TRANSACTION.set(context);
            try {
                Method operation = account.getClass().getMethod(debit(kind) ? "take" : "add", BigDecimal.class);
                Object result = operation.invoke(account, BigDecimal.valueOf(amount));
                trace(requestId, "operation result=" + result + " committed=" + context.committed
                        + " balance=" + exactMoney(data));
                if (result instanceof Boolean successful && !successful) {
                    return FutureShopsFinalEconomyBridgeService.rejected("INSUFFICIENT_FUNDS",
                            "FinalEconomy rejected the balance change");
                }
                crashIf("after_external_mutation");
                if (!context.committed) {
                    trace(requestId, "ambiguous no commit");
                    return FutureShopsFinalEconomyBridgeService.ambiguous(
                            "FinalEconomy changed the account without a durable receipt");
                }
                if (exactMoney(data) != after) {
                    trace(requestId, "ambiguous balance expected=" + after + " actual=" + exactMoney(data));
                    return FutureShopsFinalEconomyBridgeService.ambiguous(
                            "FinalEconomy balance changed after the committed receipt");
                }
                return FutureShopsFinalEconomyBridgeService.confirmedReceipt(requestId, actor, kind, amount, after);
            } catch (InvocationTargetException exception) {
                trace(requestId, "invocation exception committed=" + context.committed + " cause="
                        + exception.getCause());
                if (context.committed) {
                    return FutureShopsFinalEconomyBridgeService.ambiguous(
                            "FinalEconomy raised an exception after the durable receipt");
                }
                return FutureShopsFinalEconomyBridgeService.unavailable("FinalEconomy mutation failed before receipt");
            } catch (ReflectiveOperationException | RuntimeException exception) {
                trace(requestId, "mutation exception committed=" + context.committed + " type="
                        + exception.getClass().getName() + " message=" + exception.getMessage());
                return context.committed
                        ? FutureShopsFinalEconomyBridgeService.ambiguous(
                        "FinalEconomy mutation outcome is uncertain")
                        : FutureShopsFinalEconomyBridgeService.unavailable("FinalEconomy mutation failed");
            } finally {
                TRANSACTION.remove();
            }
        } catch (RuntimeException exception) {
            trace(requestId, "setup exception type=" + exception.getClass().getName() + " message="
                    + exception.getMessage());
            return FutureShopsFinalEconomyBridgeService.unavailable("exact FinalEconomy mutation setup failed");
        } finally {
            LEDGER_LOCK.unlock();
        }
    }

    @Override
    public Map<String, Object> lookup(Object account, UUID actor, UUID requestId, long amount, String kind) {
        if (account == null || actor == null || requestId == null || amount <= 0L || kind == null || kind.isBlank()) {
            return FutureShopsFinalEconomyBridgeService.rejected("INVALID_REQUEST", "request is invalid");
        }
        LEDGER_LOCK.lock();
        try {
            FEPlayerData data = playerData(account, actor);
            install(data);
            Receipt receipt = readReceipt(data.getConfig(), requestId);
            if (receipt == null) {
                return FutureShopsFinalEconomyBridgeService.rejected("RECEIPT_NOT_FOUND",
                        "FinalEconomy receipt was not found");
            }
            return receipt.compatible(actor, kind, amount)
                    ? FutureShopsFinalEconomyBridgeService.confirmedReceipt(requestId, receipt.actor, receipt.kind,
                    receipt.amount, receipt.after)
                    : FutureShopsFinalEconomyBridgeService.rejected("REQUEST_CONFLICT",
                    "FinalEconomy receipt identity is inconsistent");
        } catch (RuntimeException exception) {
            return FutureShopsFinalEconomyBridgeService.unavailable("FinalEconomy receipt lookup failed");
        } finally {
            LEDGER_LOCK.unlock();
        }
    }

    private boolean exactDependenciesPresent() {
        return dependencyVersion("FinalEconomy", "1.0.9")
                && dependencyVersion("EverNifeCore", "2.0.4.4")
                && dependencyVersion("PixelmonEconomyBridge", "1.1.6")
                && dependencyVersion("Vault", "1.7.3");
    }

    private void prepareProbeAccount() {
        String configured = System.getProperty("futureshops.bridge.testAccount", "").trim();
        if (configured.isEmpty()) {
            return;
        }
        try {
            UUID actor = UUID.fromString(configured);
            PlayerData playerData = PlayerController.getOrCreateOne(actor);
            if (playerData.getPlayerName() == null) {
                playerData = new PlayerData(playerData.getConfig(), "FutureShopsProbe", actor);
                PlayerController.getMapOfPlayerData().put(actor, playerData);
            }
            FEPlayerData data = playerData.getPDSection(FEPlayerData.class);
            if (data == null) {
                data = new FEPlayerData(playerData);
                playerData.getMapOfPDSections().put(FEPlayerData.class, data);
            }
            if (data == null) {
                getLogger().warning("FutureShops bridge test account could not be resolved");
                return;
            }
            LEDGER_LOCK.lock();
            try {
                install(data);
                UUID requestId = configuredProbeRequest();
                if (requestId != null && readReceipt(data.getConfig(), requestId) != null) {
                    getLogger().info("FutureShops bridge retained the bounded test account receipt");
                    return;
                }
                data.getConfig().setValue("PlayerData.Username", "FutureShopsProbe");
                data.getConfig().setValue("PlayerData.UUID", actor.toString());
                data.setMoney(100.0d);
                data.savePDSection();
                data.getConfig().save();
                try {
                    forceFile(data.getConfig().getTheFile().toPath());
                } catch (IOException exception) {
                    throw new IllegalStateException("test account durability setup failed", exception);
                }
            } finally {
                LEDGER_LOCK.unlock();
            }
            getLogger().info("FutureShops bridge prepared the bounded test account");
        } catch (RuntimeException exception) {
            getLogger().warning("FutureShops bridge test account setup failed type="
                    + exception.getClass().getName() + " message=" + String.valueOf(exception.getMessage()));
        }
    }

    private static UUID configuredProbeRequest() {
        String configured = System.getProperty("futureshops.bridge.testRequest", "").trim();
        if (configured.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(configured);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean dependencyVersion(String name, String version) {
        var plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.getDescription().getVersion().startsWith(version);
    }

    private static FEPlayerData playerData(Object account, UUID actor) {
        try {
            Object value = ACCOUNT_PLAYER_DATA.get(account);
            if (!(value instanceof FEPlayerData data) || !actor.equals(data.getUniqueId())) {
                throw new IllegalStateException("account identity mismatch");
            }
            return data;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("account data is inaccessible", exception);
        }
    }

    private static void install(FEPlayerData data) {
        WRAPPERS.computeIfAbsent(data, key -> {
            try {
                Object current = MONEY_WRAPPER.get(key);
                if (current instanceof ReceiptAwareNumberWrapper wrapper) {
                    return wrapper;
                }
                if (!(current instanceof NumberWrapper<?> number)) {
                    throw new IllegalStateException("money wrapper is not numeric");
                }
                ReceiptAwareNumberWrapper wrapper = new ReceiptAwareNumberWrapper(key, number.doubleValue());
                MONEY_WRAPPER.set(key, wrapper);
                CONFIG_LOCK.set(key.getConfig(), LEDGER_LOCK);
                return wrapper;
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("money wrapper is inaccessible", exception);
            }
        });
    }

    private static long exactMoney(FEPlayerData data) {
        double value = data.getMoney();
        if (!Double.isFinite(value) || value < 0.0d || value != Math.rint(value)
                || value > Long.MAX_VALUE || value < Long.MIN_VALUE) {
            throw new IllegalStateException("FinalEconomy raw balance is not an exact integer");
        }
        long result = (long) value;
        if ((double) result != value) {
            throw new IllegalStateException("FinalEconomy raw balance loses precision");
        }
        return result;
    }

    private static void commit(TransactionContext context, double oldValue, double newValue) {
        if (context.committed) {
            if (newValue != context.after) {
                throw new IllegalStateException("reentrant FinalEconomy mutation");
            }
            return;
        }
        if (oldValue != context.before || newValue != context.after) {
            throw new IllegalStateException("FinalEconomy mutation did not match the request");
        }
        if (!Double.isFinite(newValue) || newValue < 0.0d || newValue != Math.rint(newValue)) {
            throw new IllegalStateException("FinalEconomy mutation is not exact");
        }
        crashIf("before_receipt_persist");
        persist(context);
        context.committed = true;
        trace(context.requestId, "commit persisted before=" + context.before + " after=" + context.after);
    }

    private static void persist(TransactionContext context) {
        Config config = context.data.getConfig();
        context.data.savePDSection();
        String prefix = RECEIPT_ROOT + context.requestId;
        long imageRevision = nextImageRevision(config);
        String checksum = checksum(context, imageRevision);
        config.setValue("FutureShops.image_revision", imageRevision);
        config.setValue(prefix + ".protocol", RECEIPT_PROTOCOL);
        config.setValue(prefix + ".schema", 1);
        config.setValue(prefix + ".request_id", context.requestId.toString());
        config.setValue(prefix + ".root_request_id", context.requestId.toString());
        config.setValue(prefix + ".leg_request_id", context.requestId.toString());
        config.setValue(prefix + ".actor", context.actor.toString());
        config.setValue(prefix + ".account", context.actor.toString());
        config.setValue(prefix + ".backend_lineage", BACKEND_LINEAGE_PREFIX + context.actor);
        config.setValue(prefix + ".currency", CURRENCY_ID);
        config.setValue(prefix + ".kind", context.kind);
        config.setValue(prefix + ".operation", context.kind);
        config.setValue(prefix + ".amount", context.amount);
        config.setValue(prefix + ".before", context.before);
        config.setValue(prefix + ".after", context.after);
        config.setValue(prefix + ".definitive_result", "CONFIRMED");
        config.setValue(prefix + ".image_revision", imageRevision);
        config.setValue(prefix + ".request_fingerprint", requestFingerprint(context));
        config.setValue(prefix + ".receipt_checksum", checksum);
        config.setValue(prefix + ".external_operation_id", "finaleconomy:" + context.requestId);
        forceAtomicSave(config, context.requestId);
    }

    private static Receipt readReceipt(Config config, UUID requestId) {
        String prefix = RECEIPT_ROOT + requestId;
        Object id = config.getValue(prefix + ".request_id");
        if (id == null) {
            return null;
        }
        if (!(id instanceof String text) || !requestId.toString().equals(text)) {
            throw new IllegalStateException("FinalEconomy receipt identity is invalid");
        }
        try {
            UUID actor = UUID.fromString(String.valueOf(config.getValue(prefix + ".actor")));
            String kind = String.valueOf(config.getValue(prefix + ".kind"));
            long amount = number(config.getValue(prefix + ".amount"));
            long before = number(config.getValue(prefix + ".before"));
            long after = number(config.getValue(prefix + ".after"));
            long imageRevision = number(config.getValue(prefix + ".image_revision"));
            String protocol = String.valueOf(config.getValue(prefix + ".protocol"));
            String requestFingerprint = String.valueOf(config.getValue(prefix + ".request_fingerprint"));
            String checksum = String.valueOf(config.getValue(prefix + ".receipt_checksum"));
            long currentImageRevision = number(config.getValue("FutureShops.image_revision"));
            if (!RECEIPT_PROTOCOL.equals(protocol)
                    || !actor.toString().equals(String.valueOf(config.getValue(prefix + ".account")))
                    || !BACKEND_LINEAGE_PREFIX.concat(actor.toString())
                    .equals(String.valueOf(config.getValue(prefix + ".backend_lineage")))
                    || !CURRENCY_ID.equals(String.valueOf(config.getValue(prefix + ".currency")))
                    || !kind.equals(String.valueOf(config.getValue(prefix + ".operation")))
                    || !"CONFIRMED".equals(String.valueOf(config.getValue(prefix + ".definitive_result")))
                    || !requestId.toString().equals(String.valueOf(config.getValue(prefix + ".root_request_id")))
                    || !requestId.toString().equals(String.valueOf(config.getValue(prefix + ".leg_request_id")))
                    || currentImageRevision < imageRevision
                    || !requestFingerprint.equals(requestFingerprint(requestId, actor, kind, amount))
                    || !checksum.equals(checksum(requestId, actor, kind, amount, before, after, imageRevision))) {
                throw new IllegalStateException("FinalEconomy receipt integrity is invalid");
            }
            return new Receipt(UUID.fromString(text), actor, kind, amount, before, after, imageRevision, checksum);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("FinalEconomy receipt is corrupt", exception);
        }
    }

    private static long number(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("FinalEconomy receipt number is invalid");
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalStateException("FinalEconomy receipt number is not an exact integer", exception);
        }
    }

    private static long nextImageRevision(Config config) {
        Object value = config.getValue("FutureShops.image_revision");
        long current = value == null ? 0L : number(value);
        return Math.addExact(current, 1L);
    }

    private static String checksum(TransactionContext context, long imageRevision) {
        return checksum(context.requestId, context.actor, context.kind, context.amount, context.before,
                context.after, imageRevision);
    }

    private static String requestFingerprint(TransactionContext context) {
        return requestFingerprint(context.requestId, context.actor, context.kind, context.amount);
    }

    private static String requestFingerprint(UUID requestId, UUID actor, String kind, long amount) {
        return hash(requestId + "|" + actor + "|Optional.empty|" + amount + "|" + kind + "|" + kind);
    }

    private static String checksum(UUID requestId, UUID actor, String kind, long amount, long before, long after,
                                   long imageRevision) {
        String input = requestId + "|" + actor + "|" + kind + "|" + amount + "|" + before + "|" + after
                + "|" + imageRevision;
        return hash(input);
    }

    private static String hash(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void forceAtomicSave(Config config, UUID requestId) {
        File target = config.getTheFile();
        if (target == null) {
            throw new IllegalStateException("FinalEconomy config has no file");
        }
        Path path = target.toPath().toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalStateException("FinalEconomy config has no parent directory");
        }
        try {
            rejectSymlinkPath(path);
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".futureshops.tmp");
            try {
                Object yaml = config.getConfiguration();
                Method save = yaml.getClass().getMethod("save", File.class);
                save.invoke(yaml, temporary.toFile());
                crashIf("after_temp_write");
                forceFile(temporary);
                crashIf("after_temp_force");
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    throw new IllegalStateException("FinalEconomy filesystem lacks atomic replacement", exception);
                }
                crashIf("after_replace");
                forceDirectory(parent);
                crashIf("after_directory_force");
                if (!Files.isRegularFile(path)
                        || !Files.readString(path).contains(requestId.toString())) {
                    throw new IllegalStateException("FinalEconomy receipt readback failed");
                }
                crashIf("after_readback");
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | ReflectiveOperationException exception) {
            throw new IllegalStateException("FinalEconomy durable save failed", exception);
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException exception) {
            throw new IOException("directory force is unsupported", exception);
        }
    }

    private static void rejectSymlinkPath(Path path) {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalStateException("FinalEconomy config path contains a symbolic link");
            }
        }
    }

    private static void crashIf(String stage) {
        if (stage.equals(CRASH_STAGE)) {
            System.err.println("FutureShops bridge test crash stage " + stage);
            Runtime.getRuntime().halt(90);
        }
    }

    private static void trace(UUID requestId, String message) {
        if (TRACE) {
            System.err.println("FutureShops bridge trace request=" + requestId + " " + message);
        }
    }

    private static boolean debit(String kind) {
        return "WITHDRAW".equals(kind) || "TRANSFER_DEBIT".equals(kind) || "FEE".equals(kind);
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Field field(String className, String name) {
        try {
            return field(Class.forName(className, false, FutureShopsFinalEconomyBridgePlugin.class.getClassLoader()), name);
        } catch (ClassNotFoundException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final class ReceiptAwareNumberWrapper extends NumberWrapper<Double> {
        private final FEPlayerData data;

        private ReceiptAwareNumberWrapper(FEPlayerData data, double value) {
            super(value);
            this.data = data;
        }

        @Override
        public NumberWrapper<Double> setValue(Double value) {
            return mutate(() -> super.setValue(value));
        }

        @Override
        public NumberWrapper<Double> increment(Double value) {
            return mutate(() -> super.increment(value));
        }

        @Override
        public NumberWrapper<Double> decrement(Double value) {
            return mutate(() -> super.decrement(value));
        }

        @Override
        public NumberWrapper<Double> multiply(Double value) {
            return mutate(() -> super.multiply(value));
        }

        @Override
        public NumberWrapper<Double> divide(Double value) {
            return mutate(() -> super.divide(value));
        }

        @Override
        public NumberWrapper<Double> boundLower(Double value) {
            return mutate(() -> super.boundLower(value));
        }

        @Override
        public NumberWrapper<Double> boundUpper(Double value) {
            return mutate(() -> super.boundUpper(value));
        }

        @Override
        public NumberWrapper<Double> bound(Double lower, Double upper) {
            return mutate(() -> super.bound(lower, upper));
        }

        @Override
        public NumberWrapper<Double> normalize() {
            return mutate(() -> super.normalize());
        }

        private NumberWrapper<Double> mutate(java.util.function.Supplier<NumberWrapper<Double>> operation) {
            LEDGER_LOCK.lock();
            try {
                double before = doubleValue();
                NumberWrapper<Double> result = operation.get();
                TransactionContext context = TRANSACTION.get();
                if (context != null && context.data == data) {
                    FutureShopsFinalEconomyBridgePlugin.commit(context, before, doubleValue());
                }
                return result;
            } finally {
                LEDGER_LOCK.unlock();
            }
        }
    }

    private static final class TransactionContext {
        private final FEPlayerData data;
        private final UUID actor;
        private final UUID requestId;
        private final String kind;
        private final long amount;
        private final long before;
        private final long after;
        private boolean committed;

        private TransactionContext(FEPlayerData data, UUID actor, UUID requestId, String kind, long amount,
                                   long before, long after) {
            this.data = data;
            this.actor = actor;
            this.requestId = requestId;
            this.kind = kind;
            this.amount = amount;
            this.before = before;
            this.after = after;
        }
    }

    private record Receipt(UUID requestId, UUID actor, String kind, long amount, long before, long after,
                           long imageRevision, String checksum) {
        private boolean compatible(UUID expectedActor, String expectedKind, long expectedAmount) {
            return actor.equals(expectedActor) && kind.equals(expectedKind) && amount == expectedAmount;
        }
    }
}
