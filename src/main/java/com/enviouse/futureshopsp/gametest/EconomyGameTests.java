package com.enviouse.futureshopsp.gametest;

import com.enviouse.futureshopsp.Futureshops;
import com.enviouse.futureshopsp.api.ShopModAPI;
import com.enviouse.futureshopsp.api.economy.RequestId;
import com.enviouse.futureshopsp.api.economy.MutationKind;
import com.enviouse.futureshopsp.api.economy.MutationRequest;
import com.enviouse.futureshopsp.api.economy.ProviderError;
import com.enviouse.futureshopsp.api.economy.ProviderLifecycle;
import com.enviouse.futureshopsp.api.economy.ProviderResultStatus;
import com.enviouse.futureshopsp.compat.pixelmon.PixelmonNativeEconomyAccess;
import com.enviouse.futureshopsp.block.ShopBlockEntity;
import com.enviouse.futureshopsp.init.ModBlocks;
import com.enviouse.futureshopsp.init.ModItems;
import com.enviouse.futureshopsp.server.economy.BalanceManager;
import com.enviouse.futureshopsp.server.economy.ClaimState;
import com.enviouse.futureshopsp.server.economy.EconomyClaimSavedData;
import com.enviouse.futureshopsp.server.shop.PlayerShopBarterEscrowSavedData;
import com.enviouse.futureshopsp.server.shop.PlayerShopBlockService;
import com.enviouse.futureshopsp.server.shop.PlayerShopSaleEscrowSavedData;
import com.enviouse.futureshopsp.server.session.ShopSessionManager;
import com.enviouse.futureshopsp.server.transaction.ShopSellService;
import com.enviouse.futureshopsp.server.transaction.ShopBuyService;
import com.enviouse.futureshopsp.network.packets.C2SSellRequestPacket;
import com.enviouse.futureshopsp.network.packets.C2SBuyRequestPacket;
import com.enviouse.futureshopsp.catalog.ShopCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.List;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Real server proof that the mod and its default economy are ready together. */
@GameTestHolder(Futureshops.MODID)
@PrefixGameTestTemplate(false)
public final class EconomyGameTests {
    private static final UUID FIXTURE_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000231");
    private static final UUID RESTART_REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000241");
    private static final Logger LOGGER = LogUtils.getLogger();

    private EconomyGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void internalEconomyAndRegistration(GameTestHelper helper) {
        if (!"internal".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }
        helper.assertTrue(ModList.get().isLoaded(Futureshops.MODID),
                "FutureShops must be loaded in the GameTest server");
        ResourceLocation moneyId = BuiltInRegistries.ITEM.getKey(ModItems.MONEY_ITEM.get());
        helper.assertTrue(ResourceLocation.fromNamespaceAndPath(Futureshops.MODID, "money").equals(moneyId),
                "the save compatible money item must be registered");

        var lifecycle = BalanceManager.getLifecycleSnapshotOrUnresolved();
        helper.assertTrue(lifecycle.providerId().equals("internal"),
                "a fresh GameTest server must select the internal provider");
        helper.assertTrue(lifecycle.lifecycle() == ProviderLifecycle.READY,
                "the internal provider must be ready before a GameTest mutation");

        var setResult = BalanceManager.setInternalBalance(FIXTURE_PLAYER, 231L);
        helper.assertTrue(setResult.confirmed(), "the internal provider must accept a server mutation");
        var queryResult = BalanceManager.queryBalance(FIXTURE_PLAYER);
        helper.assertTrue(queryResult.confirmed() && queryResult.value().orElseThrow().balanceMinorUnits() == 231L,
                "the server query must return the confirmed balance");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void publicMutationRoutesUseDurableCoordinator(GameTestHelper helper) {
        if (!"internal".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }
        UUID payer = UUID.fromString("00000000-0000-0000-0000-000000000238");
        UUID recipient = UUID.fromString("00000000-0000-0000-0000-000000000239");
        helper.assertTrue(BalanceManager.setInternalBalance(payer, 1_000L).confirmed(),
                "the fixture payer balance must be initialized");
        helper.assertTrue(BalanceManager.setInternalBalance(recipient, 0L).confirmed(),
                "the fixture recipient balance must be initialized");

        var withdrawal = BalanceManager.withdraw(payer, 250L);
        helper.assertTrue(withdrawal.success() && withdrawal.resultingBalance() == 750L,
                "public withdrawals must use the durable coordinator balance");

        var deposit = BalanceManager.deposit(recipient, 250L);
        helper.assertTrue(deposit.success() && deposit.resultingBalance() == 250L,
                "public deposits must use the durable coordinator balance");

        var transfer = BalanceManager.transfer(payer, recipient, 100L);
        helper.assertTrue(transfer.success() && transfer.resultingBalance() == 650L,
                "public transfers must persist both coordinator legs");
        helper.assertTrue(BalanceManager.queryBalance(recipient).value().orElseThrow().balanceMinorUnits() == 350L,
                "the recipient balance must include the confirmed transfer credit");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pixelmonPlayerMutationRefusal(GameTestHelper helper) {
        if (!"pixelmon".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        MutationRequest request = MutationRequest.forPlayer(RequestId.random(), player.getUUID(),
                25L, MutationKind.WITHDRAW);
        try {
            Object storage = pixelmonPartyStorage(player);
            storage.getClass().getMethod("setBalance", BigDecimal.class).invoke(storage, BigDecimal.valueOf(100L));
            var query = BalanceManager.queryBalance(player.getUUID());
            helper.assertTrue(query.confirmed() && query.value().orElseThrow().balanceMinorUnits() == 100L,
                    "the exact Pixelmon provider must answer the live native account balance");
            Object account = pixelmonBankAccount(player);
            var preflight = BalanceManager.getCoordinator().preflight(request);
            LOGGER.info("futureshops.pixelmon.gametest account={} native_access={} preflight={}",
                    account.getClass().getName(), account instanceof com.enviouse.futureshopsp.compat.pixelmon.PixelmonNativeEconomyAccess,
                    preflight.error());
            if (preflight.error() == ProviderError.CAPABILITY_MISSING) {
                var mutation = BalanceManager.getCoordinator().withdraw(request);
                helper.assertTrue(mutation.error() == ProviderError.CAPABILITY_MISSING,
                        "an unmixined Pixelmon account must refuse before external mutation");
                helper.assertTrue(BalanceManager.getCoordinator().custody(request.requestId().child("custody")).isEmpty(),
                        "Pixelmon mutation refusal must not create item custody");
            } else {
                helper.assertTrue(preflight.confirmed(),
                        "the native Pixelmon account must pass the mutation preflight");
                var mutation = BalanceManager.getCoordinator().withdraw(request);
                helper.assertTrue(mutation.confirmed(),
                        "the native Pixelmon account must confirm the request aware mutation");
                var replay = BalanceManager.getCoordinator().withdraw(request);
                helper.assertTrue(replay.confirmed() && replay.receipt().equals(mutation.receipt()),
                        "the native Pixelmon account must deduplicate the request UUID");
                var after = BalanceManager.queryBalance(player.getUUID());
                helper.assertTrue(after.confirmed() && after.value().orElseThrow().balanceMinorUnits() == 75L,
                        "the native Pixelmon mutation must debit the provider account once");
                CompoundTag saved = (CompoundTag) storage.getClass()
                        .getMethod("writeToNBT", CompoundTag.class, HolderLookup.Provider.class)
                        .invoke(storage, new CompoundTag(), helper.getLevel().registryAccess());
                helper.assertTrue(saved.contains("FutureShopsReceipts"),
                        "the native Pixelmon receipt must be stored beside pixelDollars");
                CompoundTag persisted = readPixelmonStorageFile(storage);
                helper.assertTrue(hasCompletedReceipt(persisted, request.requestId()),
                        "the native Pixelmon receipt must be present in the durable storage file");
                Object restoredStorage = storage.getClass().getConstructor(UUID.class).newInstance(player.getUUID());
                Object restoredFuture = storage.getClass()
                        .getMethod("readFromNBT", CompoundTag.class, HolderLookup.Provider.class)
                        .invoke(restoredStorage, saved.copy(), helper.getLevel().registryAccess());
                Object restored = ((CompletableFuture<?>) restoredFuture).join();
                helper.assertTrue(restored instanceof PixelmonNativeEconomyAccess,
                        "the reloaded native Pixelmon account must retain the FutureShops access hook");
                var restoredReceipt = ((PixelmonNativeEconomyAccess) restored).futureshopsLookup(request.requestId());
                helper.assertTrue(restoredReceipt.confirmed() && restoredReceipt.receipt().equals(mutation.receipt()),
                        "the native Pixelmon receipt must survive a storage reload");
                var restoredReplay = ((PixelmonNativeEconomyAccess) restored).futureshopsMutate(request.requestId(),
                        request.kind(), request.amountMinorUnits(), helper.getLevel().registryAccess());
                helper.assertTrue(restoredReplay.confirmed() && restoredReplay.receipt().equals(mutation.receipt()),
                        "the native Pixelmon request must deduplicate after a storage reload");
                BigDecimal restoredBalance = (BigDecimal) restoredStorage.getClass().getMethod("getBalance")
                        .invoke(restoredStorage);
                helper.assertTrue(restoredBalance.longValueExact() == 75L,
                        "the native Pixelmon balance must survive a storage reload");

                CompoundTag unknownRecord = saved.copy();
                unknownRecord.getCompound("FutureShopsReceipts").getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        .add(new CompoundTag());
                Object corruptedStorage = storage.getClass().getConstructor(UUID.class).newInstance(player.getUUID());
                Object corruptedFuture = storage.getClass()
                        .getMethod("readFromNBT", CompoundTag.class, HolderLookup.Provider.class)
                        .invoke(corruptedStorage, unknownRecord, helper.getLevel().registryAccess());
                Object corrupted = ((CompletableFuture<?>) corruptedFuture).join();
                var corruptedResult = ((PixelmonNativeEconomyAccess) corrupted).futureshopsLookup(request.requestId());
                helper.assertTrue(corruptedResult.status() == ProviderResultStatus.RECOVERY_REQUIRED,
                        "an unknown native Pixelmon receipt record must force recovery");

                CompoundTag wrongTypeRecord = saved.copy();
                ListTag wrongTypeEntries = new ListTag();
                wrongTypeEntries.add(StringTag.valueOf("unknown receipt type"));
                wrongTypeRecord.getCompound("FutureShopsReceipts").put("entries", wrongTypeEntries);
                Object wrongTypeStorage = storage.getClass().getConstructor(UUID.class).newInstance(player.getUUID());
                Object wrongTypeFuture = storage.getClass()
                        .getMethod("readFromNBT", CompoundTag.class, HolderLookup.Provider.class)
                        .invoke(wrongTypeStorage, wrongTypeRecord, helper.getLevel().registryAccess());
                Object wrongType = ((CompletableFuture<?>) wrongTypeFuture).join();
                var wrongTypeResult = ((PixelmonNativeEconomyAccess) wrongType).futureshopsLookup(request.requestId());
                helper.assertTrue(wrongTypeResult.status() == ProviderResultStatus.RECOVERY_REQUIRED,
                        "a non compound native Pixelmon receipt entry must force recovery");
                CompoundTag wrongRootRecord = saved.copy();
                wrongRootRecord.put("FutureShopsReceipts", StringTag.valueOf("unknown receipt root type"));
                Object wrongRootStorage = storage.getClass().getConstructor(UUID.class).newInstance(player.getUUID());
                Object wrongRootFuture = wrongRootStorage.getClass()
                        .getMethod("readFromNBT", CompoundTag.class, HolderLookup.Provider.class)
                        .invoke(wrongRootStorage, wrongRootRecord, helper.getLevel().registryAccess());
                Object wrongRoot = ((CompletableFuture<?>) wrongRootFuture).join();
                var wrongRootResult = ((PixelmonNativeEconomyAccess) wrongRoot).futureshopsLookup(request.requestId());
                helper.assertTrue(wrongRootResult.status() == ProviderResultStatus.RECOVERY_REQUIRED,
                        "a non compound native Pixelmon receipt root must force recovery");
                LOGGER.info("futureshops.pixelmon.gametest native mutation confirmed request={} replay={} balance={} receipt_nbt={} reload={} reconnect_replay={} reloaded_balance={} unknown_recovery={} wrong_type_recovery={} wrong_root_recovery={}",
                        request.requestId().value(), replay.receipt().orElseThrow().requestId().value(),
                        after.value().orElseThrow().balanceMinorUnits(), saved.contains("FutureShopsReceipts"),
                        restoredReceipt.status(), restoredReplay.status(), restoredBalance, corruptedResult.status(),
                        wrongTypeResult.status(), wrongRootResult.status());
            }
        } catch (ReflectiveOperationException | IOException | RuntimeException exception) {
            helper.fail("the exact Pixelmon native account probe failed: " + exception.getClass().getSimpleName());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pixelmonNativeProcessRestart(GameTestHelper helper) {
        if (!"pixelmon".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }

        Path marker = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("futureshops-pixelmon-restart.marker");
        try {
            if (!Files.exists(marker)) {
                ServerPlayer player = helper.makeMockServerPlayerInLevel();
                MutationRequest request = MutationRequest.forPlayer(new RequestId(RESTART_REQUEST), player.getUUID(),
                        25L, MutationKind.WITHDRAW);
                Object storage = pixelmonPartyStorage(player);
                storage.getClass().getMethod("setBalance", BigDecimal.class).invoke(storage, BigDecimal.valueOf(100L));
                var mutation = BalanceManager.getCoordinator().withdraw(request);
                helper.assertTrue(mutation.confirmed(), "the first restart pass must confirm native mutation");
                helper.assertTrue(hasCompletedReceipt(readPixelmonStorageFile(storage), request.requestId()),
                        "the first restart pass must persist its completed receipt");
                Files.writeString(marker, player.getUUID() + "\n", StandardCharsets.UTF_8);
                LOGGER.info("futureshops.pixelmon.gametest process_restart phase=FIRST request={} balance=75 receipt=COMPLETED",
                        request.requestId().value());
            } else {
                UUID playerId = UUID.fromString(Files.readString(marker, StandardCharsets.UTF_8).trim());
                Class<?> storageType = Class.forName("com.pixelmonmod.pixelmon.api.storage.PlayerPartyStorage");
                Object storage = storageType.getConstructor(UUID.class).newInstance(playerId);
                CompoundTag persisted = readPixelmonStorageFile(storage);
                Object restoredFuture = storage.getClass()
                        .getMethod("readFromNBT", CompoundTag.class, HolderLookup.Provider.class)
                        .invoke(storage, persisted.copy(), helper.getLevel().registryAccess());
                Object restored = ((CompletableFuture<?>) restoredFuture).join();
                helper.assertTrue(restored instanceof PixelmonNativeEconomyAccess,
                        "the restarted native Pixelmon account must retain the receipt access hook");
                var replay = ((PixelmonNativeEconomyAccess) restored).futureshopsMutate(new RequestId(RESTART_REQUEST),
                        MutationKind.WITHDRAW, 25L, helper.getLevel().registryAccess());
                helper.assertTrue(replay.confirmed(), "the restarted native Pixelmon request must replay safely");
                BigDecimal balance = (BigDecimal) storage.getClass().getMethod("getBalance").invoke(storage);
                helper.assertTrue(balance.longValueExact() == 75L,
                        "the restarted native Pixelmon balance must remain debited once");
                LOGGER.info("futureshops.pixelmon.gametest process_restart phase=SECOND request={} replay={} balance={}",
                        RESTART_REQUEST, replay.status(), balance);
                Files.deleteIfExists(marker);
            }
        } catch (ReflectiveOperationException | IOException | RuntimeException exception) {
            helper.fail("the native Pixelmon process restart probe failed: " + exception.getClass().getSimpleName());
            return;
        }
        helper.succeed();
    }

    private static Object pixelmonPartyStorage(ServerPlayer player) throws ReflectiveOperationException {
        Class<?> proxy = Class.forName("com.pixelmonmod.pixelmon.api.storage.StorageProxy");
        for (Method method : proxy.getMethods()) {
            if (method.getName().equals("getPartyNow") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(player.getClass())) {
                Object storage = method.invoke(null, player);
                if (storage != null) {
                    return storage;
                }
            }
        }
        throw new IllegalStateException("Pixelmon party storage is unavailable");
    }

    private static CompoundTag readPixelmonStorageFile(Object storage)
            throws ReflectiveOperationException, IOException {
        Class<?> storageType = Class.forName("com.pixelmonmod.pixelmon.api.storage.PokemonStorage");
        Object adapter = Class.forName("com.pixelmonmod.pixelmon.api.storage.StorageProxy")
                .getMethod("getSaveAdapter").invoke(null);
        if (adapter == null) {
            throw new IllegalStateException("Pixelmon save adapter is unavailable");
        }
        Object fileValue = adapter.getClass().getMethod("getFile", storageType).invoke(adapter, storage);
        if (!(fileValue instanceof File file)) {
            throw new IllegalStateException("Pixelmon storage file is unavailable");
        }
        return NbtIo.read(file.toPath());
    }

    private static boolean hasCompletedReceipt(CompoundTag persisted, RequestId requestId) {
        if (persisted == null || !persisted.contains("FutureShopsReceipts", Tag.TAG_COMPOUND)) {
            return false;
        }
        CompoundTag root = persisted.getCompound("FutureShopsReceipts");
        if (!root.contains("entries", Tag.TAG_LIST)) {
            return false;
        }
        ListTag entries = root.getList("entries", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            if (entry.hasUUID("request_id") && requestId.value().equals(entry.getUUID("request_id"))
                    && "COMPLETED".equals(entry.getString("state"))) {
                return true;
            }
        }
        return false;
    }

    private static void setNativeBalance(ServerPlayer player, long balance) throws ReflectiveOperationException {
        Object storage = pixelmonPartyStorage(player);
        storage.getClass().getMethod("setBalance", BigDecimal.class)
                .invoke(storage, BigDecimal.valueOf(balance));
    }

    private static Object pixelmonBankAccount(ServerPlayer player) throws ReflectiveOperationException {
        Class<?> proxy = Class.forName("com.pixelmonmod.pixelmon.api.economy.BankAccountProxy");
        for (Method method : proxy.getMethods()) {
            if (method.getName().equals("getBankAccountNow") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(player.getClass())) {
                Object account = method.invoke(null, player);
                if (account != null) {
                    return account;
                }
            }
        }
        throw new IllegalStateException("Pixelmon bank account is unavailable");
    }

    private static boolean isNativePixelmonAccount(ServerPlayer player) {
        try {
            return pixelmonBankAccount(player) instanceof com.enviouse.futureshopsp.compat.pixelmon.PixelmonNativeEconomyAccess;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pixelmonServerShopSellRefusalBeforeItemRemoval(GameTestHelper helper) {
        if (!"pixelmon".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }

        helper.assertTrue(ShopCatalog.getItem("default", "minecraft:diamond").isPresent(),
                "the disposable server shop must expose the diamond sell listing");
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        if (isNativePixelmonAccount(player)) {
            try {
                setNativeBalance(player, 0L);
                player.getInventory().add(new ItemStack(Items.IRON_INGOT, 1));
                int beforeItems = player.getInventory().countItem(Items.IRON_INGOT);
                int beforeStock = ShopCatalog.getCurrentStock("default", "minecraft:iron_ingot");
                ShopSessionManager.open(player.getUUID(), "default");
                try {
                    ShopSellService.handleSellRequest(player,
                            new C2SSellRequestPacket("default", "minecraft:iron_ingot", 1));
                } finally {
                    ShopSessionManager.close(player.getUUID());
                }
                long afterBalance = BalanceManager.queryBalance(player.getUUID()).value().orElseThrow().balanceMinorUnits();
                LOGGER.info("futureshops.pixelmon.gametest native server sell state items_before={} items_after={} balance={} stock_before={} stock_after={}",
                        beforeItems, player.getInventory().countItem(Items.IRON_INGOT), afterBalance, beforeStock,
                        ShopCatalog.getCurrentStock("default", "minecraft:iron_ingot"));
                helper.assertTrue(player.getInventory().countItem(Items.IRON_INGOT) == beforeItems - 1,
                        "native Pixelmon server shop sell must remove the sold item after provider confirmation");
                helper.assertTrue(afterBalance == 25L,
                        "native Pixelmon server shop sell must credit the exact provider amount");
                helper.assertTrue(ShopCatalog.getCurrentStock("default", "minecraft:iron_ingot") == beforeStock + 1,
                        "native Pixelmon server shop sell must increment finite stock");
                helper.assertTrue(!BalanceManager.getCustodyStore().hasIncompleteRecords(),
                        "native Pixelmon server shop sell must release custody");
                LOGGER.info("futureshops.pixelmon.gametest native server sell confirmed balance={} stock_before={} stock_after={}",
                        afterBalance, beforeStock, ShopCatalog.getCurrentStock("default", "minecraft:iron_ingot"));
            } catch (ReflectiveOperationException exception) {
                helper.fail("the native Pixelmon server shop sell probe failed: " + exception.getClass().getSimpleName());
                return;
            }
            helper.succeed();
            return;
        }
        player.getInventory().add(new ItemStack(Items.DIAMOND, 1));
        int before = player.getInventory().countItem(Items.DIAMOND);
        ShopSessionManager.open(player.getUUID(), "default");
        try {
            ShopSellService.handleSellRequest(player,
                    new C2SSellRequestPacket("default", "minecraft:diamond", 1));
        } finally {
            ShopSessionManager.close(player.getUUID());
        }
        helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == before,
                "Pixelmon shop sell refusal must not remove the offered item");
        helper.assertTrue(!BalanceManager.getCustodyStore().hasIncompleteRecords(),
                "Pixelmon shop sell refusal must not create custody");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pixelmonPlayerShopBuyRefusalBeforeSaleEscrow(GameTestHelper helper) {
        if (!"pixelmon".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }

        BlockPos relativeShopPos = new BlockPos(1, 1, 1);
        BlockPos shopPos = helper.absolutePos(relativeShopPos);
        helper.setBlock(relativeShopPos, ModBlocks.SHOP_BLOCK.get().defaultBlockState());
        ShopBlockEntity shop = helper.getBlockEntity(relativeShopPos);
        helper.assertTrue(shop != null, "the player shop block entity must be available");
        shop.setPlacedByCreative(true);
        helper.assertTrue(shop.setAdminShopMode(true), "the disposable shop must enter admin mode");
        shop.setOwnerUuid(UUID.fromString("00000000-0000-0000-0000-000000000240"));
        int listingIndex = shop.addOrSelectListing("minecraft:diamond");
        ShopBlockEntity.Listing listing = shop.getListing(listingIndex);
        helper.assertTrue(listing != null, "the disposable admin shop must have a listing");
        listing.setTradeMode(ShopBlockEntity.TradeMode.MONEY);
        listing.setMoneyPriceMinor(1L);
        listing.setBaseQuantity(1);

        ServerPlayer buyer = helper.makeMockServerPlayerInLevel();
        if (isNativePixelmonAccount(buyer)) {
            try {
                setNativeBalance(buyer, 100L);
                int diamondsBefore = buyer.getInventory().countItem(Items.DIAMOND);
                int escrowRecordsBefore = PlayerShopSaleEscrowSavedData.get(buyer.getServer()).snapshot().size();
                PlayerShopBlockService.buy(buyer, shopPos, listingIndex, 1, "MONEY");
                long afterBalance = BalanceManager.queryBalance(buyer.getUUID()).value().orElseThrow().balanceMinorUnits();
                List<PlayerShopSaleEscrowSavedData.EscrowRecord> escrowRecords =
                        PlayerShopSaleEscrowSavedData.get(buyer.getServer()).snapshot();
                LOGGER.info("futureshops.pixelmon.gametest native admin buy state diamonds_before={} diamonds_after={} balance={} escrow_before={} escrow_after={}",
                        diamondsBefore, buyer.getInventory().countItem(Items.DIAMOND), afterBalance,
                        escrowRecordsBefore, escrowRecords.size());
                helper.assertTrue(buyer.getInventory().countItem(Items.DIAMOND) == diamondsBefore + 1,
                        "native Pixelmon admin shop buy must deliver the item after provider confirmation");
                helper.assertTrue(afterBalance == 99L,
                        "native Pixelmon admin shop buy must debit the exact provider amount");
                helper.assertTrue(escrowRecords.size() == escrowRecordsBefore + 1
                                && escrowRecords.get(escrowRecords.size() - 1).state()
                                == PlayerShopSaleEscrowSavedData.State.CLAIMED,
                        "native Pixelmon admin shop buy must finish its durable sale escrow");
                helper.assertTrue(!BalanceManager.getCustodyStore().hasIncompleteRecords(),
                        "native Pixelmon admin shop buy must claim custody");
                LOGGER.info("futureshops.pixelmon.gametest native admin shop buy confirmed balance={} escrow_state={}",
                        afterBalance, escrowRecords.get(escrowRecords.size() - 1).state());
            } catch (ReflectiveOperationException exception) {
                helper.fail("the native Pixelmon admin shop buy probe failed: " + exception.getClass().getSimpleName());
                return;
            }
            helper.succeed();
            return;
        }
        int diamondsBefore = buyer.getInventory().countItem(Items.DIAMOND);
        int escrowRecordsBefore = PlayerShopSaleEscrowSavedData.get(buyer.getServer()).snapshot().size();
        PlayerShopBlockService.buy(buyer, shopPos, listingIndex, 1, "MONEY");

        helper.assertTrue(buyer.getInventory().countItem(Items.DIAMOND) == diamondsBefore,
                "Pixelmon player shop buy refusal must not deliver an item");
        helper.assertTrue(PlayerShopSaleEscrowSavedData.get(buyer.getServer()).snapshot().size() == escrowRecordsBefore,
                "Pixelmon player shop buy refusal must not prepare sale escrow");
        helper.assertTrue(!BalanceManager.getCustodyStore().hasIncompleteRecords(),
                "Pixelmon player shop buy refusal must not create custody");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pixelmonMoneyItemRefusalBeforeConsumption(GameTestHelper helper) {
        if (!"pixelmon".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ItemStack money = new ItemStack(ModItems.MONEY_ITEM.get(), 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, money);
        int before = money.getCount();

        var result = ModItems.MONEY_ITEM.get().use(player.level(), player, InteractionHand.MAIN_HAND);

        helper.assertTrue(result.getResult().consumesAction() == false,
                "Pixelmon money item use must refuse without consuming the item");
        helper.assertTrue(money.getCount() == before,
                "Pixelmon money item refusal must preserve the item stack");
        helper.assertTrue(!BalanceManager.getCustodyStore().hasIncompleteRecords(),
                "Pixelmon money item refusal must not create custody");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pixelmonCartAndPhysicalCommandRefusal(GameTestHelper helper) {
        if (!"pixelmon".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }

        helper.assertTrue(ShopCatalog.getItem("default", "minecraft:diamond").isPresent(),
                "the disposable server shop must expose the diamond cart listing");
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        int diamondsBefore = player.getInventory().countItem(Items.DIAMOND);
        int stockBefore = ShopCatalog.getCurrentStock("default", "minecraft:diamond");
        ShopSessionManager.open(player.getUUID(), "default");
        try {
            ShopBuyService.handleBuyRequest(player,
                    C2SBuyRequestPacket.cart("default",
                            List.of(new C2SBuyRequestPacket.LineItem("minecraft:diamond", 1))));
        } finally {
            ShopSessionManager.close(player.getUUID());
        }
        helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == diamondsBefore,
                "Pixelmon cart refusal must not deliver an item");
        helper.assertTrue(ShopCatalog.getCurrentStock("default", "minecraft:diamond") == stockBefore,
                "Pixelmon cart refusal must not reserve stock");
        helper.assertTrue(!BalanceManager.getCustodyStore().hasIncompleteRecords(),
                "Pixelmon cart refusal must not create custody");

        ItemStack money = new ItemStack(ModItems.MONEY_ITEM.get(), 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, money);
        int moneyBefore = money.getCount();
        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack(), "withdraw 1");
        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack(), "deposit");
        helper.assertTrue(money.getCount() == moneyBefore,
                "Pixelmon withdraw command must refuse before minting bills");
        helper.assertTrue(player.getInventory().countItem(ModItems.MONEY_ITEM.get()) == 1,
                "Pixelmon deposit command must refuse before consuming money");
        helper.assertTrue(!BalanceManager.getCustodyStore().hasIncompleteRecords(),
                "Pixelmon physical commands must not create custody");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pixelmonCartMutationSuccess(GameTestHelper helper) {
        if (!"pixelmon".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        if (!isNativePixelmonAccount(player)) {
            helper.succeed();
            return;
        }
        try {
            setNativeBalance(player, 1_000L);
            int diamondsBefore = player.getInventory().countItem(Items.DIAMOND);
            ShopSessionManager.open(player.getUUID(), "default");
            try {
                ShopBuyService.handleBuyRequest(player,
                        C2SBuyRequestPacket.cart("default",
                                List.of(new C2SBuyRequestPacket.LineItem("minecraft:diamond", 1))));
            } finally {
                ShopSessionManager.close(player.getUUID());
            }
            long afterBalance = BalanceManager.queryBalance(player.getUUID()).value().orElseThrow().balanceMinorUnits();
            LOGGER.info("futureshops.pixelmon.gametest native cart state diamonds_before={} diamonds_after={} balance={}",
                    diamondsBefore, player.getInventory().countItem(Items.DIAMOND), afterBalance);
            helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == diamondsBefore + 1,
                    "native Pixelmon cart must deliver every prepared reward");
            helper.assertTrue(afterBalance == 500L,
                    "native Pixelmon cart must debit the exact aggregate price");
            helper.assertTrue(!BalanceManager.getCustodyStore().hasIncompleteRecords(),
                    "native Pixelmon cart must claim delivery custody");
            LOGGER.info("futureshops.pixelmon.gametest native cart confirmed balance={} delivered={}",
                    afterBalance, player.getInventory().countItem(Items.DIAMOND) - diamondsBefore);
        } catch (ReflectiveOperationException exception) {
            helper.fail("the native Pixelmon cart probe failed: " + exception.getClass().getSimpleName());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void pixelmonHybridShopBuyAndSellSuccess(GameTestHelper helper) {
        if (!"pixelmon".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        if (isNativePixelmonAccount(player)) {
            helper.succeed();
            return;
        }

        var seed = BalanceManager.deposit(player.getUUID(), 1_000L);
        helper.assertTrue(seed.success(),
                "the exact hybrid account must accept a bound seed deposit before shop verification");
        long seededBalance = BalanceManager.queryBalance(player.getUUID()).value().orElseThrow().balanceMinorUnits();
        helper.assertTrue(seededBalance == 1_000L,
                "the exact hybrid account must expose the bound seed balance");

        int diamondsBefore = player.getInventory().countItem(Items.DIAMOND);
        ShopSessionManager.open(player.getUUID(), "default");
        try {
            ShopBuyService.handleBuyRequest(player,
                    C2SBuyRequestPacket.cart("default",
                            List.of(new C2SBuyRequestPacket.LineItem("minecraft:diamond", 1))));
        } finally {
            ShopSessionManager.close(player.getUUID());
        }
        long afterBuy = BalanceManager.queryBalance(player.getUUID()).value().orElseThrow().balanceMinorUnits();
        helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == diamondsBefore + 1,
                "the exact hybrid shop buy must deliver the purchased item");
        helper.assertTrue(afterBuy == 500L,
                "the exact hybrid shop buy must debit the bound provider balance once");

        player.getInventory().add(new ItemStack(Items.IRON_INGOT, 1));
        int ironBefore = player.getInventory().countItem(Items.IRON_INGOT);
        ShopSessionManager.open(player.getUUID(), "default");
        try {
            ShopSellService.handleSellRequest(player,
                    new C2SSellRequestPacket("default", "minecraft:iron_ingot", 1));
        } finally {
            ShopSessionManager.close(player.getUUID());
        }
        long afterSell = BalanceManager.queryBalance(player.getUUID()).value().orElseThrow().balanceMinorUnits();
        helper.assertTrue(player.getInventory().countItem(Items.IRON_INGOT) == ironBefore - 1,
                "the exact hybrid shop sell must remove the sold item after confirmation");
        helper.assertTrue(afterSell == 525L,
                "the exact hybrid shop sell must credit the bound provider balance once");
        helper.assertTrue(!BalanceManager.getCustodyStore().hasIncompleteRecords(),
                "the exact hybrid shop buy and sell must release custody");
        LOGGER.info("futureshops.pixelmon.gametest hybrid shop buy and sell confirmed balance={} account_class=custom_or_hybrid",
                afterSell);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pixelmonPayTransferSuccess(GameTestHelper helper) {
        if (!"pixelmon".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }
        ServerPlayer payer = helper.makeMockServerPlayerInLevel();
        ServerPlayer recipient = helper.makeMockServerPlayerInLevel();
        if (!isNativePixelmonAccount(payer) || !isNativePixelmonAccount(recipient)) {
            helper.succeed();
            return;
        }
        try {
            setNativeBalance(payer, 1_000L);
            setNativeBalance(recipient, 0L);
            RequestId probeRoot = RequestId.random();
            var payerPreflight = BalanceManager.getCoordinator().preflight(new MutationRequest(
                    probeRoot.child("probe debit"), payer.getUUID(), java.util.Optional.of(recipient.getUUID()),
                    125L, MutationKind.TRANSFER_DEBIT));
            var recipientPreflight = BalanceManager.getCoordinator().preflight(new MutationRequest(
                    probeRoot.child("probe credit"), recipient.getUUID(), java.util.Optional.of(payer.getUUID()),
                    125L, MutationKind.TRANSFER_CREDIT));
            var result = BalanceManager.transfer(payer.getUUID(), recipient.getUUID(), 125L);
            LOGGER.info("futureshops.pixelmon.gametest native pay state same_account={} payer_preflight={} recipient_preflight={} success={} error={} payer_result={} payer_live={} recipient_live={}",
                    payer.getUUID().equals(recipient.getUUID()),
                    payerPreflight.error(), recipientPreflight.error(),
                    result.success(), result.errorCode(), result.resultingBalance(),
                    BalanceManager.queryBalance(payer.getUUID()).value().orElseThrow().balanceMinorUnits(),
                    BalanceManager.queryBalance(recipient.getUUID()).value().orElseThrow().balanceMinorUnits());
            helper.assertTrue(result.success() && result.resultingBalance() == 875L,
                    "native Pixelmon pay transfer must debit the payer exactly once");
            helper.assertTrue(BalanceManager.queryBalance(recipient.getUUID()).value().orElseThrow().balanceMinorUnits() == 125L,
                    "native Pixelmon pay transfer must credit the recipient exactly once");
            helper.assertTrue(!BalanceManager.getCustodyStore().hasIncompleteRecords(),
                    "native Pixelmon pay transfer must not leave custody");
            LOGGER.info("futureshops.pixelmon.gametest native pay transfer confirmed payer={} recipient={}",
                    result.resultingBalance(), BalanceManager.queryBalance(recipient.getUUID()).value().orElseThrow().balanceMinorUnits());
        } catch (ReflectiveOperationException exception) {
            helper.fail("the native Pixelmon pay transfer probe failed: " + exception.getClass().getSimpleName());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pixelmonPublicMutationKindsPersistReceipts(GameTestHelper helper) {
        if (!"pixelmon".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        if (!isNativePixelmonAccount(player)) {
            helper.succeed();
            return;
        }
        try {
            setNativeBalance(player, 200L);
            var withdrawal = BalanceManager.withdraw(player.getUUID(), 50L);
            var deposit = BalanceManager.deposit(player.getUUID(), 10L);
            RequestId root = RequestId.random();
            MutationRequest refundRequest = MutationRequest.forPlayer(root.child("refund"), player.getUUID(),
                    20L, MutationKind.REFUND);
            MutationRequest compensationRequest = MutationRequest.forPlayer(root.child("compensation"),
                    player.getUUID(), 5L, MutationKind.COMPENSATION);
            var refund = BalanceManager.getCoordinator().refund(refundRequest);
            var compensation = BalanceManager.getCoordinator().compensate(compensationRequest);
            long balance = BalanceManager.queryBalance(player.getUUID()).value().orElseThrow().balanceMinorUnits();
            LOGGER.info("futureshops.pixelmon.gametest native public mutations withdrawal={} deposit={} refund={} compensation={} balance={}",
                    withdrawal.success(), deposit.success(), refund.status(), compensation.status(), balance);
            helper.assertTrue(withdrawal.success() && withdrawal.resultingBalance() == 150L,
                    "native Pixelmon public withdrawal must debit once");
            helper.assertTrue(deposit.success() && deposit.resultingBalance() == 160L,
                    "native Pixelmon public deposit must credit once");
            helper.assertTrue(refund.confirmed() && compensation.confirmed(),
                    "native Pixelmon refund and compensation must confirm through the durable coordinator");
            helper.assertTrue(balance == 185L,
                    "native Pixelmon public mutation kinds must preserve the exact resulting balance");
            helper.assertTrue(!BalanceManager.getCustodyStore().hasIncompleteRecords(),
                    "native Pixelmon public mutation kinds must not leave custody");
        } catch (ReflectiveOperationException exception) {
            helper.fail("the native Pixelmon public mutation probe failed: " + exception.getClass().getSimpleName());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void twoServerPlayersKeepIndependentAuthoritativeBalances(GameTestHelper helper) {
        if (!"internal".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }
        ServerPlayer buyer = helper.makeMockServerPlayerInLevel();
        ServerPlayer seller = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(!buyer.getUUID().equals(seller.getUUID()),
                "the multiplayer fixture must create distinct server players");
        helper.assertTrue(BalanceManager.setInternalBalance(buyer.getUUID(), 400L).confirmed(),
                "the buyer balance must be initialized");
        helper.assertTrue(BalanceManager.setInternalBalance(seller.getUUID(), 100L).confirmed(),
                "the seller balance must be initialized");

        RequestId buyerRequest = RequestId.random();
        MutationRequest withdrawal = MutationRequest.forPlayer(buyerRequest, buyer.getUUID(),
                150L, MutationKind.WITHDRAW);
        var first = BalanceManager.getCoordinator().withdraw(withdrawal);
        helper.assertTrue(first.confirmed(), "the buyer request must be confirmed");
        helper.assertTrue(BalanceManager.queryBalance(buyer.getUUID()).value().orElseThrow().balanceMinorUnits() == 250L,
                "the buyer request must change only the buyer balance");
        helper.assertTrue(BalanceManager.queryBalance(seller.getUUID()).value().orElseThrow().balanceMinorUnits() == 100L,
                "the buyer request must not change the seller balance");

        RequestId sellerRequest = RequestId.random();
        MutationRequest deposit = MutationRequest.forPlayer(sellerRequest, seller.getUUID(),
                150L, MutationKind.DEPOSIT);
        helper.assertTrue(BalanceManager.getCoordinator().deposit(deposit).confirmed(),
                "the seller request must be confirmed");
        helper.assertTrue(BalanceManager.queryBalance(seller.getUUID()).value().orElseThrow().balanceMinorUnits() == 250L,
                "the seller request must change only the seller balance");
        var replay = BalanceManager.getCoordinator().withdraw(withdrawal);
        helper.assertTrue(replay.confirmed() && replay.receipt().equals(first.receipt()),
                "a second player reconnect must replay the original buyer receipt");
        helper.assertTrue(BalanceManager.queryBalance(buyer.getUUID()).value().orElseThrow().balanceMinorUnits() == 250L,
                "a replay must not debit the buyer twice");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void playerShopSaleEscrowLifecycle(GameTestHelper helper) {
        PlayerShopSaleEscrowSavedData escrow = new PlayerShopSaleEscrowSavedData();
        UUID buyer = UUID.fromString("00000000-0000-0000-0000-000000000232");
        UUID request = UUID.fromString("00000000-0000-0000-0000-000000000233");
        ItemStack reward = new ItemStack(Items.DIAMOND, 2);
        var registryAccess = helper.getLevel().registryAccess();

        helper.assertTrue(escrow.prepare(request, buyer, 7L, "minecraft:overworld",
                        "minecraft:diamond", 2L, List.of(reward), registryAccess),
                "sale escrow must persist the exact reward before removal");
        CompoundTag saved = escrow.save(new CompoundTag(), registryAccess);
        PlayerShopSaleEscrowSavedData restored = PlayerShopSaleEscrowSavedData.load(saved, registryAccess);
        helper.assertTrue(restored.integrityValid(), "sale escrow checksum must survive a world save");
        helper.assertTrue(restored.markRemoved(request, List.of(reward), registryAccess),
                "sale escrow must verify the removed reward");
        helper.assertTrue(restored.markDelivered(request), "sale escrow must record delivery");
        helper.assertTrue(restored.markClaimed(request), "sale escrow must record the final claim");
        helper.assertTrue(!restored.hasIncompleteRecords(), "completed sale escrow must not remain pending");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void playerShopSaleEscrowUncleanRestartPreservesRecoveryState(GameTestHelper helper) {
        PlayerShopSaleEscrowSavedData escrow = new PlayerShopSaleEscrowSavedData();
        UUID buyer = UUID.fromString("00000000-0000-0000-0000-000000000234");
        UUID request = UUID.fromString("00000000-0000-0000-0000-000000000235");
        ItemStack reward = new ItemStack(Items.DIAMOND, 1);
        var registryAccess = helper.getLevel().registryAccess();

        helper.assertTrue(escrow.prepare(request, buyer, 8L, "minecraft:overworld",
                        "minecraft:diamond", 1L, List.of(reward), registryAccess),
                "sale escrow must persist intent before an admitted effect");
        escrow.markUnclean();

        PlayerShopSaleEscrowSavedData recovered = PlayerShopSaleEscrowSavedData.load(
                escrow.save(new CompoundTag(), registryAccess), registryAccess);
        helper.assertTrue(recovered.integrityValid(), "unclean sale escrow must retain valid checksums");
        helper.assertTrue(!recovered.cleanMarkerValid(), "unclean save must require recovery");
        helper.assertTrue(recovered.hasIncompleteRecords(), "unresolved sale custody must remain pending");
        helper.assertTrue(recovered.markRecoveryRequired(request),
                "recovery must classify the interrupted sale escrow explicitly");
        helper.assertTrue(recovered.find(request).orElseThrow().state()
                        == PlayerShopSaleEscrowSavedData.State.RECOVERY_REQUIRED,
                "interrupted sale escrow must not be retried as prepared work");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void playerShopSaleEscrowRemovedUncleanRestartPreservesRecoveryState(GameTestHelper helper) {
        PlayerShopSaleEscrowSavedData escrow = new PlayerShopSaleEscrowSavedData();
        UUID buyer = UUID.fromString("00000000-0000-0000-0000-000000000244");
        UUID request = UUID.fromString("00000000-0000-0000-0000-000000000245");
        ItemStack reward = new ItemStack(Items.DIAMOND, 2);
        var registryAccess = helper.getLevel().registryAccess();

        helper.assertTrue(escrow.prepare(request, buyer, 10L, "minecraft:overworld",
                        "minecraft:diamond", 2L, List.of(reward), registryAccess),
                "sale escrow must persist intent before item removal");
        helper.assertTrue(escrow.markRemoved(request, List.of(reward), registryAccess),
                "sale escrow must persist the exact removed reward");
        escrow.markUnclean();

        PlayerShopSaleEscrowSavedData recovered = PlayerShopSaleEscrowSavedData.load(
                escrow.save(new CompoundTag(), registryAccess), registryAccess);
        helper.assertTrue(recovered.integrityValid(), "removed sale escrow must retain valid checksums");
        helper.assertTrue(!recovered.cleanMarkerValid(), "removed sale save must require recovery");
        helper.assertTrue(recovered.find(request).orElseThrow().state()
                        == PlayerShopSaleEscrowSavedData.State.REMOVED,
                "removed sale escrow must preserve its exact interrupted state");
        helper.assertTrue(recovered.decodeStacks(request, registryAccess).orElseThrow().get(0).getCount() == 2,
                "removed sale escrow must preserve the exact reward stack");
        helper.assertTrue(recovered.markRecoveryRequired(request),
                "removed sale escrow must be frozen before any retry");
        helper.assertTrue(recovered.find(request).orElseThrow().state()
                        == PlayerShopSaleEscrowSavedData.State.RECOVERY_REQUIRED,
                "removed sale escrow must not be retried as delivered work");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void playerShopSaleEscrowDeliveredUncleanRestartPreservesRecoveryState(GameTestHelper helper) {
        PlayerShopSaleEscrowSavedData escrow = new PlayerShopSaleEscrowSavedData();
        UUID buyer = UUID.fromString("00000000-0000-0000-0000-000000000246");
        UUID request = UUID.fromString("00000000-0000-0000-0000-000000000247");
        ItemStack reward = new ItemStack(Items.DIAMOND, 1);
        var registryAccess = helper.getLevel().registryAccess();

        helper.assertTrue(escrow.prepare(request, buyer, 11L, "minecraft:overworld",
                        "minecraft:diamond", 1L, List.of(reward), registryAccess),
                "sale escrow must persist intent before item removal");
        helper.assertTrue(escrow.markRemoved(request, List.of(reward), registryAccess),
                "sale escrow must persist the exact removed reward");
        helper.assertTrue(escrow.markDelivered(request),
                "sale escrow must persist delivery before claim completion");
        escrow.markUnclean();

        PlayerShopSaleEscrowSavedData recovered = PlayerShopSaleEscrowSavedData.load(
                escrow.save(new CompoundTag(), registryAccess), registryAccess);
        helper.assertTrue(recovered.integrityValid(), "delivered sale escrow must retain valid checksums");
        helper.assertTrue(!recovered.cleanMarkerValid(), "delivered sale save must require recovery");
        helper.assertTrue(recovered.find(request).orElseThrow().state()
                        == PlayerShopSaleEscrowSavedData.State.DELIVERED,
                "delivered sale escrow must preserve its exact interrupted state");
        helper.assertTrue(recovered.markRecoveryRequired(request),
                "delivered sale escrow must be frozen before claim replay");
        helper.assertTrue(recovered.find(request).orElseThrow().state()
                        == PlayerShopSaleEscrowSavedData.State.RECOVERY_REQUIRED,
                "delivered sale escrow must not be retried as a new delivery");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void playerShopBarterEscrowUncleanRestartPreservesRecoveryState(GameTestHelper helper) {
        PlayerShopBarterEscrowSavedData escrow = new PlayerShopBarterEscrowSavedData();
        UUID buyer = UUID.fromString("00000000-0000-0000-0000-000000000236");
        UUID request = UUID.fromString("00000000-0000-0000-0000-000000000237");
        ItemStack payment = new ItemStack(Items.EMERALD, 3);
        var registryAccess = helper.getLevel().registryAccess();

        helper.assertTrue(escrow.prepare(request, buyer, 9L, "minecraft:overworld",
                        "minecraft:emerald", 3, List.of(payment), registryAccess),
                "barter escrow must persist the exact payment before removal");
        helper.assertTrue(escrow.markRemoved(request, List.of(payment), registryAccess),
                "barter escrow must verify the removed payment");
        helper.assertTrue(escrow.markStored(request),
                "barter escrow must record stored custody before the dependent leg");
        escrow.markUnclean();

        PlayerShopBarterEscrowSavedData recovered = PlayerShopBarterEscrowSavedData.load(
                escrow.save(new CompoundTag(), registryAccess), registryAccess);
        helper.assertTrue(recovered.integrityValid(), "unclean barter escrow must retain valid checksums");
        helper.assertTrue(!recovered.cleanMarkerValid(), "unclean barter save must require recovery");
        helper.assertTrue(recovered.hasIncompleteRecords(), "stored barter custody must remain pending");
        helper.assertTrue(recovered.markRecoveryRequired(request),
                "recovery must classify the interrupted barter escrow explicitly");
        helper.assertTrue(recovered.find(request).state()
                        == PlayerShopBarterEscrowSavedData.State.RECOVERY_REQUIRED,
                "interrupted barter escrow must not be retried as stored work");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void playerShopBarterEscrowRemovedUncleanRestartPreservesRecoveryState(GameTestHelper helper) {
        PlayerShopBarterEscrowSavedData escrow = new PlayerShopBarterEscrowSavedData();
        UUID buyer = UUID.fromString("00000000-0000-0000-0000-000000000248");
        UUID request = UUID.fromString("00000000-0000-0000-0000-000000000249");
        ItemStack payment = new ItemStack(Items.EMERALD, 2);
        var registryAccess = helper.getLevel().registryAccess();

        helper.assertTrue(escrow.prepare(request, buyer, 12L, "minecraft:overworld",
                        "minecraft:emerald", 2, List.of(payment), registryAccess),
                "barter escrow must persist intent before payment removal");
        helper.assertTrue(escrow.markRemoved(request, List.of(payment), registryAccess),
                "barter escrow must persist the exact removed payment");
        escrow.markUnclean();

        PlayerShopBarterEscrowSavedData recovered = PlayerShopBarterEscrowSavedData.load(
                escrow.save(new CompoundTag(), registryAccess), registryAccess);
        helper.assertTrue(recovered.integrityValid(), "removed barter escrow must retain valid checksums");
        helper.assertTrue(!recovered.cleanMarkerValid(), "removed barter save must require recovery");
        helper.assertTrue(recovered.find(request).state() == PlayerShopBarterEscrowSavedData.State.REMOVED,
                "removed barter escrow must preserve its exact interrupted state");
        helper.assertTrue(recovered.markRecoveryRequired(request),
                "removed barter escrow must be frozen before any retry");
        helper.assertTrue(recovered.find(request).state()
                        == PlayerShopBarterEscrowSavedData.State.RECOVERY_REQUIRED,
                "removed barter escrow must not be retried as stored work");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void offlineClaimUncleanRestartPreservesRecoveryState(GameTestHelper helper) {
        EconomyClaimSavedData claims = new EconomyClaimSavedData();
        UUID claimant = UUID.fromString("00000000-0000-0000-0000-000000000240");
        RequestId request = new RequestId(UUID.fromString("00000000-0000-0000-0000-000000000241"));
        var registryAccess = helper.getLevel().registryAccess();

        helper.assertTrue(claims.create(request, claimant, 45L, "offline proceeds").state() == ClaimState.PENDING,
                "offline proceeds must begin as a pending durable claim");
        claims.markUnclean();

        EconomyClaimSavedData recovered = EconomyClaimSavedData.load(
                claims.save(new CompoundTag(), registryAccess), registryAccess);
        helper.assertTrue(recovered.integrityValid(), "unclean claims must retain valid checksums");
        helper.assertTrue(!recovered.cleanMarkerValid(), "unclean claims must require recovery");
        helper.assertTrue(recovered.hasIncompleteRecords(), "pending proceeds must remain recoverable");
        helper.assertTrue(recovered.find(request).orElseThrow().state() == ClaimState.PENDING,
                "recovery must preserve the original claim identity and pending state");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void reconnectReplayPreservesStableRequestIdentity(GameTestHelper helper) {
        if (!"internal".equals(BalanceManager.getLifecycleSnapshotOrUnresolved().providerId())) {
            helper.succeed();
            return;
        }
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000242");
        RequestId request = new RequestId(UUID.randomUUID());
        helper.assertTrue(BalanceManager.setInternalBalance(player, 500L).confirmed(),
                "the reconnect fixture balance must be initialized");

        MutationRequest withdrawal = MutationRequest.forPlayer(request, player, 125L, MutationKind.WITHDRAW);
        var first = BalanceManager.getCoordinator().withdraw(withdrawal);
        helper.assertTrue(first.confirmed(), "the initial request must be confirmed");
        helper.assertTrue(BalanceManager.queryBalance(player).value().orElseThrow().balanceMinorUnits() == 375L,
                "the initial request must debit the authoritative balance once");

        // A reconnect resubmits the same server owned request identity.
        var replay = BalanceManager.getCoordinator().withdraw(withdrawal);
        helper.assertTrue(replay.confirmed(), "a reconnect replay must return the confirmed result");
        helper.assertTrue(replay.receipt().equals(first.receipt()),
                "a reconnect replay must return the original receipt");
        helper.assertTrue(BalanceManager.queryBalance(player).value().orElseThrow().balanceMinorUnits() == 375L,
                "a reconnect replay must not debit the balance twice");
        helper.succeed();
    }
}
