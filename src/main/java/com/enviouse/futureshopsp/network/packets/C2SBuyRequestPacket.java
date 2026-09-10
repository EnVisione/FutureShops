package com.enviouse.futureshopsp.network.packets;

import com.enviouse.futureshopsp.server.transaction.ShopBuyService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.enviouse.futureshopsp.Futureshops;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Client → server buy request.
 * Supports both single-item buys (detail screen) and cart checkout.
 */
public record C2SBuyRequestPacket(String shopId, boolean cartCheckout, List<LineItem> lineItems,
                                  long snapshotRevision) implements CustomPacketPayload {
    public static final Type<C2SBuyRequestPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Futureshops.MODID, "c2sbuyrequestpacket"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SBuyRequestPacket> STREAM_CODEC = StreamCodec.ofMember(C2SBuyRequestPacket::encode, C2SBuyRequestPacket::decode);

    public C2SBuyRequestPacket(String shopId, boolean cartCheckout, List<LineItem> lineItems) {
        this(shopId, cartCheckout, lineItems, 0L);
    }

    @Override
    public Type<C2SBuyRequestPacket> type() {
        return TYPE;
    }


    /**
     * One buy line. {@code listingId} is the catalog resolution key (== registry itemId for legacy
     * single-variant listings, a distinct stable id for multi-variant NBT listings) — the server
     * resolves the exact {@code ItemDef} by it, then mints from that def's registry itemId + NBT.
     */
    public record LineItem(String listingId, int quantity) {
        public static void encode(FriendlyByteBuf buffer, LineItem lineItem) {
            buffer.writeUtf(lineItem.listingId, 128);
            buffer.writeVarInt(lineItem.quantity);
        }

        public static LineItem decode(FriendlyByteBuf buffer) {
            return new LineItem(buffer.readUtf(128), buffer.readVarInt());
        }
    }

    public static C2SBuyRequestPacket single(String shopId, String listingId, int quantity) {
        return single(shopId, listingId, quantity, 0L);
    }

    public static C2SBuyRequestPacket single(String shopId, String listingId, int quantity, long snapshotRevision) {
        return new C2SBuyRequestPacket(shopId, false, List.of(new LineItem(listingId, quantity)), snapshotRevision);
    }

    public static C2SBuyRequestPacket cart(String shopId, List<LineItem> lineItems) {
        return cart(shopId, lineItems, 0L);
    }

    public static C2SBuyRequestPacket cart(String shopId, List<LineItem> lineItems, long snapshotRevision) {
        return new C2SBuyRequestPacket(shopId, true, List.copyOf(lineItems), snapshotRevision);
    }

    public static void encode(C2SBuyRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.shopId, 128);
        buffer.writeBoolean(packet.cartCheckout);
        buffer.writeCollection(packet.lineItems, LineItem::encode);
        buffer.writeLong(packet.snapshotRevision);
    }

    /** Hard cap on cart line count. A real cart never exceeds a few dozen;
     *  bounds a malicious varInt to a finite allocation. */
    private static final int MAX_LINES = 256;

    public static C2SBuyRequestPacket decode(FriendlyByteBuf buffer) {
        String shopId = buffer.readUtf(128);
        boolean cartCheckout = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_LINES) {
            throw new io.netty.handler.codec.DecoderException(
                    "C2SBuyRequestPacket lines out of range: " + count);
        }
        java.util.List<LineItem> lines = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(LineItem.decode(buffer));
        }
        return new C2SBuyRequestPacket(shopId, cartCheckout, lines, buffer.readLong());
    }

    public static void handle(C2SBuyRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (player != null) {
                ShopBuyService.handleBuyRequest(player, packet);
            }
        });
    }
}
