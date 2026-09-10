package com.enviouse.futureshopsp.network.packets;

import com.enviouse.futureshopsp.client.ShopClientPacketHandler;
import com.enviouse.futureshopsp.data.CatalogBarterRecipe;
import com.enviouse.futureshopsp.data.CatalogCategory;
import com.enviouse.futureshopsp.data.CatalogItem;
import com.enviouse.futureshopsp.data.CatalogPromo;
import com.enviouse.futureshopsp.data.NearbyShopEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.enviouse.futureshopsp.Futureshops;

import java.util.List;
import java.util.UUID;

/**
 * S2C packet sent after a successful {@link C2SOpenShopPacket}.
 * Carries the full shop catalog (categories, items, promos, barter recipes) together with
 * the player's current balance, display-currency metadata, nearby player shops, and admin toggle.
 *
     * <p>Protocol version 26, added economy status and snapshot revision fields so unavailable
     * balances and stale client actions are explicit.
     * The {@code forceOpen} flag also keeps silent refreshes (stock refresh,
 * post-transaction sync, admin reload) can update an already-open screen without spawning one
 * for players whose session is still alive but who have closed the GUI.
 */
public record S2CShopDataPacket(
        String shopId,
        long balanceMinorUnits,
        String currencyName,
        int currencyDecimals,
        List<CatalogCategory> categories,
        List<CatalogItem> items,
        List<CatalogPromo> promos,
        List<CatalogBarterRecipe> barterRecipes,
        boolean adminShopEnabled,
        List<NearbyShopEntry> nearbyShops,
        boolean forceOpen,
        boolean balanceAvailable,
        String providerId,
        String providerLifecycle,
        String providerDiagnostic,
        long snapshotRevision) implements CustomPacketPayload {
    public static final Type<S2CShopDataPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Futureshops.MODID, "s2cshopdatapacket"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CShopDataPacket> STREAM_CODEC = StreamCodec.ofMember(S2CShopDataPacket::encode, S2CShopDataPacket::decode);

    public S2CShopDataPacket(String shopId, long balanceMinorUnits, String currencyName, int currencyDecimals,
                             List<CatalogCategory> categories, List<CatalogItem> items,
                             List<CatalogPromo> promos, List<CatalogBarterRecipe> barterRecipes,
                             boolean adminShopEnabled, List<NearbyShopEntry> nearbyShops, boolean forceOpen,
                             boolean balanceAvailable, String providerId, String providerLifecycle,
                             String providerDiagnostic) {
        this(shopId, balanceMinorUnits, currencyName, currencyDecimals, categories, items, promos, barterRecipes,
                adminShopEnabled, nearbyShops, forceOpen, balanceAvailable, providerId, providerLifecycle,
                providerDiagnostic, 0L);
    }

    @Override
    public Type<S2CShopDataPacket> type() {
        return TYPE;
    }


    /** Backward-compat constructor without nearby shops / admin toggle / forceOpen flag. */
    public S2CShopDataPacket(String shopId, long balanceMinorUnits, String currencyName, int currencyDecimals,
                             List<CatalogCategory> categories, List<CatalogItem> items,
                             List<CatalogPromo> promos, List<CatalogBarterRecipe> barterRecipes) {
        this(shopId, balanceMinorUnits, currencyName, currencyDecimals, categories, items, promos, barterRecipes,
                true, List.of(), true, true, "internal", "READY", "");
    }

    /** Backward-compat constructor without forceOpen (defaults to true — preserves legacy open behavior). */
    public S2CShopDataPacket(String shopId, long balanceMinorUnits, String currencyName, int currencyDecimals,
                             List<CatalogCategory> categories, List<CatalogItem> items,
                             List<CatalogPromo> promos, List<CatalogBarterRecipe> barterRecipes,
                             boolean adminShopEnabled, List<NearbyShopEntry> nearbyShops) {
        this(shopId, balanceMinorUnits, currencyName, currencyDecimals, categories, items, promos, barterRecipes,
                adminShopEnabled, nearbyShops, true, true, "internal", "READY", "");
    }

    public S2CShopDataPacket(String shopId, long balanceMinorUnits, String currencyName, int currencyDecimals,
                             List<CatalogCategory> categories, List<CatalogItem> items,
                             List<CatalogPromo> promos, List<CatalogBarterRecipe> barterRecipes,
                             boolean adminShopEnabled, List<NearbyShopEntry> nearbyShops, boolean forceOpen) {
        this(shopId, balanceMinorUnits, currencyName, currencyDecimals, categories, items, promos, barterRecipes,
                adminShopEnabled, nearbyShops, forceOpen, true, "internal", "READY", "");
    }

    public static void encode(S2CShopDataPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.shopId);
        buffer.writeLong(packet.balanceMinorUnits);
        buffer.writeUtf(packet.currencyName);
        buffer.writeVarInt(packet.currencyDecimals);
        buffer.writeCollection(packet.categories, CatalogCategory::encode);
        buffer.writeCollection(packet.items, CatalogItem::encode);
        buffer.writeCollection(packet.promos, CatalogPromo::encode);
        buffer.writeCollection(packet.barterRecipes, CatalogBarterRecipe::encode);
        buffer.writeBoolean(packet.adminShopEnabled);
        buffer.writeVarInt(packet.nearbyShops.size());
        for (NearbyShopEntry entry : packet.nearbyShops) {
            buffer.writeBlockPos(entry.pos());
            buffer.writeUUID(entry.ownerUuid());
            buffer.writeUtf(entry.ownerName());
            buffer.writeUtf(entry.shopName());
            buffer.writeVarInt(entry.listingCount());
            buffer.writeVarInt(entry.totalStock());
            buffer.writeDouble(entry.distance());
        }
        buffer.writeBoolean(packet.forceOpen);
        buffer.writeBoolean(packet.balanceAvailable);
        buffer.writeUtf(packet.providerId, 128);
        buffer.writeUtf(packet.providerLifecycle, 32);
        buffer.writeUtf(packet.providerDiagnostic, 256);
        buffer.writeLong(packet.snapshotRevision);
    }

    public static S2CShopDataPacket decode(FriendlyByteBuf buffer) {
        String shopId = buffer.readUtf();
        long balance = buffer.readLong();
        String currencyName = buffer.readUtf();
        int decimals = buffer.readVarInt();
        List<CatalogCategory> categories = buffer.readList(CatalogCategory::decode);
        List<CatalogItem> items = buffer.readList(CatalogItem::decode);
        List<CatalogPromo> promos = buffer.readList(CatalogPromo::decode);
        List<CatalogBarterRecipe> barterRecipes = buffer.readList(CatalogBarterRecipe::decode);
        boolean adminShopEnabled = buffer.readBoolean();
        int nearbyCount = buffer.readVarInt();
        List<NearbyShopEntry> nearbyShops = new java.util.ArrayList<>();
        for (int i = 0; i < nearbyCount; i++) {
            BlockPos pos = buffer.readBlockPos();
            UUID ownerUuid = buffer.readUUID();
            String ownerName = buffer.readUtf();
            String shopName = buffer.readUtf();
            int listingCount = buffer.readVarInt();
            int totalStock = buffer.readVarInt();
            double distance = buffer.readDouble();
            nearbyShops.add(new NearbyShopEntry(pos, ownerUuid, ownerName, shopName, listingCount, totalStock, distance));
        }
        boolean forceOpen = buffer.readBoolean();
        boolean balanceAvailable = buffer.readBoolean();
        String providerId = buffer.readUtf(128);
        String providerLifecycle = buffer.readUtf(32);
        String providerDiagnostic = buffer.readUtf(256);
        long snapshotRevision = buffer.readLong();
        return new S2CShopDataPacket(shopId, balance, currencyName, decimals, categories, items, promos, barterRecipes,
                adminShopEnabled, nearbyShops, forceOpen, balanceAvailable, providerId, providerLifecycle,
                providerDiagnostic, snapshotRevision);
    }

    public static void handle(S2CShopDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                ShopClientPacketHandler.handleShopData(packet));
    }
}
