package com.enviouse.futureshopsp.network.packets;

import com.enviouse.futureshopsp.client.ShopClientPacketHandler;
import com.enviouse.futureshopsp.server.shop.ShopResultCode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.enviouse.futureshopsp.Futureshops;


/**
 * Server → client response for a buy request.
 * The authoritative post-transaction catalog refresh still arrives via S2CShopDataPacket.
 */
public record S2CBuyResponsePacket(
        boolean success,
        boolean cartCheckout,
        String shopId,
        ShopResultCode errorCode,
        long resultingBalanceMinorUnits,
        int totalQuantity,
        long totalMinorUnits,
        boolean balanceAvailable,
        long snapshotRevision,
        String responseReason) implements CustomPacketPayload {
    public static final Type<S2CBuyResponsePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Futureshops.MODID, "s2cbuyresponsepacket"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CBuyResponsePacket> STREAM_CODEC = StreamCodec.ofMember(S2CBuyResponsePacket::encode, S2CBuyResponsePacket::decode);

    public S2CBuyResponsePacket(boolean success, boolean cartCheckout, String shopId, ShopResultCode errorCode,
                                long resultingBalanceMinorUnits, int totalQuantity, long totalMinorUnits) {
        this(success, cartCheckout, shopId, errorCode, resultingBalanceMinorUnits, totalQuantity, totalMinorUnits, true, 0L, "");
    }

    @Override
    public Type<S2CBuyResponsePacket> type() {
        return TYPE;
    }


    public static void encode(S2CBuyResponsePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.success);
        buffer.writeBoolean(packet.cartCheckout);
        buffer.writeUtf(packet.shopId);
        // Serialize by name for enum-reorder tolerance.
        buffer.writeUtf(packet.errorCode.name());
        buffer.writeLong(packet.resultingBalanceMinorUnits);
        buffer.writeVarInt(packet.totalQuantity);
        buffer.writeLong(packet.totalMinorUnits);
        buffer.writeBoolean(packet.balanceAvailable);
        buffer.writeLong(packet.snapshotRevision);
        buffer.writeUtf(packet.responseReason, 64);
    }

    public static S2CBuyResponsePacket decode(FriendlyByteBuf buffer) {
        boolean success = buffer.readBoolean();
        boolean cartCheckout = buffer.readBoolean();
        String shopId = buffer.readUtf();
        String rawCode = buffer.readUtf();
        ShopResultCode code;
        try {
            code = ShopResultCode.valueOf(rawCode);
        } catch (IllegalArgumentException ex) {
            code = ShopResultCode.SERVER_ERROR;
        }
        long bal = buffer.readLong();
        int totalQty = buffer.readVarInt();
        long totalMu = buffer.readLong();
        boolean balanceAvailable = buffer.readBoolean();
        long snapshotRevision = buffer.readLong();
        String responseReason = buffer.readUtf(64);
        return new S2CBuyResponsePacket(success, cartCheckout, shopId, code, bal, totalQty, totalMu, balanceAvailable,
                snapshotRevision, responseReason);
    }

    public static void handle(S2CBuyResponsePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ShopClientPacketHandler.handleBuyResponse(packet));
    }
}
