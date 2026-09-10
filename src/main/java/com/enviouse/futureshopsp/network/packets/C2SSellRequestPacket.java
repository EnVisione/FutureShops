package com.enviouse.futureshopsp.network.packets;

import com.enviouse.futureshopsp.server.transaction.ShopSellService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.enviouse.futureshopsp.Futureshops;
import net.minecraft.server.level.ServerPlayer;


/**
 * Client → server sell request from ItemDetailScreen. {@code listingId} is the catalog resolution
 * key; the server resolves the exact listing by it, then counts/removes from the player using that
 * listing's registry itemId + NBT.
 */
public record C2SSellRequestPacket(String shopId, String listingId, int quantity,
                                   long snapshotRevision) implements CustomPacketPayload {
    public static final Type<C2SSellRequestPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Futureshops.MODID, "c2ssellrequestpacket"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SSellRequestPacket> STREAM_CODEC = StreamCodec.ofMember(C2SSellRequestPacket::encode, C2SSellRequestPacket::decode);

    @Override
    public Type<C2SSellRequestPacket> type() {
        return TYPE;
    }

    public C2SSellRequestPacket(String shopId, String listingId, int quantity) {
        this(shopId, listingId, quantity, 0L);
    }

    public static void encode(C2SSellRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.shopId, 128);
        buffer.writeUtf(packet.listingId, 128);
        buffer.writeVarInt(packet.quantity);
        buffer.writeLong(packet.snapshotRevision);
    }

    public static C2SSellRequestPacket decode(FriendlyByteBuf buffer) {
        return new C2SSellRequestPacket(buffer.readUtf(128), buffer.readUtf(128), buffer.readVarInt(), buffer.readLong());
    }

    public static void handle(C2SSellRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player != null) {
                ShopSellService.handleSellRequest(player, packet);
            }
        });
    }
}
