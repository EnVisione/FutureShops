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


/** Server → client response for a sell request. */
public record S2CSellResponsePacket(
        boolean success,
        String shopId,
        String itemId,
        ShopResultCode errorCode,
        long resultingBalanceMinorUnits,
        int quantity,
        long totalMinorUnits,
        boolean balanceAvailable,
        long snapshotRevision,
        String responseReason) implements CustomPacketPayload {
    public static final Type<S2CSellResponsePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Futureshops.MODID, "s2csellresponsepacket"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CSellResponsePacket> STREAM_CODEC = StreamCodec.ofMember(S2CSellResponsePacket::encode, S2CSellResponsePacket::decode);

    public S2CSellResponsePacket(boolean success, String shopId, String itemId, ShopResultCode errorCode,
                                 long resultingBalanceMinorUnits, int quantity, long totalMinorUnits) {
        this(success, shopId, itemId, errorCode, resultingBalanceMinorUnits, quantity, totalMinorUnits, true, 0L, "");
    }

    @Override
    public Type<S2CSellResponsePacket> type() {
        return TYPE;
    }


    public static void encode(S2CSellResponsePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.success);
        buffer.writeUtf(packet.shopId);
        buffer.writeUtf(packet.itemId);
        // Serialize by name for enum-reorder tolerance.
        buffer.writeUtf(packet.errorCode.name());
        buffer.writeLong(packet.resultingBalanceMinorUnits);
        buffer.writeVarInt(packet.quantity);
        buffer.writeLong(packet.totalMinorUnits);
        buffer.writeBoolean(packet.balanceAvailable);
        buffer.writeLong(packet.snapshotRevision);
        buffer.writeUtf(packet.responseReason, 64);
    }

    public static S2CSellResponsePacket decode(FriendlyByteBuf buffer) {
        boolean success = buffer.readBoolean();
        String shopId = buffer.readUtf();
        String itemId = buffer.readUtf();
        String rawCode = buffer.readUtf();
        ShopResultCode code;
        try {
            code = ShopResultCode.valueOf(rawCode);
        } catch (IllegalArgumentException ex) {
            code = ShopResultCode.SERVER_ERROR;
        }
        long bal = buffer.readLong();
        int qty = buffer.readVarInt();
        long totalMu = buffer.readLong();
        boolean balanceAvailable = buffer.readBoolean();
        long snapshotRevision = buffer.readLong();
        String responseReason = buffer.readUtf(64);
        return new S2CSellResponsePacket(success, shopId, itemId, code, bal, qty, totalMu, balanceAvailable,
                snapshotRevision, responseReason);
    }

    public static void handle(S2CSellResponsePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ShopClientPacketHandler.handleSellResponse(packet));
    }
}
