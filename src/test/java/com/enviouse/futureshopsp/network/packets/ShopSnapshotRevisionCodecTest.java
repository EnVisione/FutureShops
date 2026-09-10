package com.enviouse.futureshopsp.network.packets;

import com.enviouse.futureshopsp.server.shop.ShopResultCode;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ShopSnapshotRevisionCodecTest {

    @Test
    void buyRequestCarriesSnapshotRevision() {
        C2SBuyRequestPacket packet = C2SBuyRequestPacket.single("default", "minecraft:stone", 2, 41L);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            C2SBuyRequestPacket.encode(packet, buffer);
            C2SBuyRequestPacket decoded = C2SBuyRequestPacket.decode(buffer);
            assertEquals(41L, decoded.snapshotRevision());
            assertEquals(packet.lineItems(), decoded.lineItems());
        } finally {
            buffer.release();
        }
    }

    @Test
    void sellRequestCarriesSnapshotRevision() {
        C2SSellRequestPacket packet = new C2SSellRequestPacket("default", "minecraft:stone", 3, 99L);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            C2SSellRequestPacket.encode(packet, buffer);
            C2SSellRequestPacket decoded = C2SSellRequestPacket.decode(buffer);
            assertEquals(99L, decoded.snapshotRevision());
            assertEquals(packet.listingId(), decoded.listingId());
        } finally {
            buffer.release();
        }
    }

    @Test
    void responseCarriesTypedStaleReasonAndRevision() {
        S2CBuyResponsePacket packet = new S2CBuyResponsePacket(false, false, "default",
                ShopResultCode.STALE_REQUEST, 12L, 0, 0L, true, 100L, "stale_snapshot");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2CBuyResponsePacket.encode(packet, buffer);
            S2CBuyResponsePacket decoded = S2CBuyResponsePacket.decode(buffer);
            assertFalse(decoded.success());
            assertEquals(ShopResultCode.STALE_REQUEST, decoded.errorCode());
            assertEquals(100L, decoded.snapshotRevision());
            assertEquals("stale_snapshot", decoded.responseReason());
        } finally {
            buffer.release();
        }
    }

    @Test
    void shopDataCarriesRevision() {
        S2CShopDataPacket packet = new S2CShopDataPacket("default", 12L, "Coins", 2,
                List.of(), List.of(), List.of(), List.of(), true, List.of(), false, true,
                "pixelmon", "READY", "", 17L);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2CShopDataPacket.encode(packet, buffer);
            S2CShopDataPacket decoded = S2CShopDataPacket.decode(buffer);
            assertEquals(17L, decoded.snapshotRevision());
            assertEquals("pixelmon", decoded.providerId());
        } finally {
            buffer.release();
        }
    }
}
