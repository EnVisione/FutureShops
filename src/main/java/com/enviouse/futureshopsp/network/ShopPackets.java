package com.enviouse.futureshopsp.network;

import com.enviouse.futureshopsp.Futureshops;
import com.enviouse.futureshopsp.network.packets.C2SBarterRequestPacket;
import com.enviouse.futureshopsp.network.packets.C2SBuyRequestPacket;
import com.enviouse.futureshopsp.network.packets.C2SFetchDepartmentsPacket;
import com.enviouse.futureshopsp.network.packets.C2SFetchHistoryPacket;
import com.enviouse.futureshopsp.network.packets.C2SFetchLocalShopsPacket;
import com.enviouse.futureshopsp.network.packets.C2SFetchSettlementHistoryPacket;
import com.enviouse.futureshopsp.network.packets.C2SFranchiseActionPacket;
import com.enviouse.futureshopsp.network.packets.C2SInventorySyncPacket;
import com.enviouse.futureshopsp.network.packets.C2SOpenBalanceUiPacket;
import com.enviouse.futureshopsp.network.packets.C2SOpenBalTopUiPacket;
import com.enviouse.futureshopsp.network.packets.C2SOpenShopPacket;
import com.enviouse.futureshopsp.network.packets.C2SPlayerShopActionPacket;
import com.enviouse.futureshopsp.network.packets.C2SPlayerShopBuybackConfigPacket;
import com.enviouse.futureshopsp.network.packets.C2SPlayerShopBuyPacket;
import com.enviouse.futureshopsp.network.packets.C2SPlayerShopConfigPacket;
import com.enviouse.futureshopsp.network.packets.C2SPlayerShopPromoPacket;
import com.enviouse.futureshopsp.network.packets.C2SPlayerShopSellPacket;
import com.enviouse.futureshopsp.network.packets.C2SSellRequestPacket;
import com.enviouse.futureshopsp.network.packets.C2SSetDepartmentPacket;
import com.enviouse.futureshopsp.network.packets.C2SVerifyAdminCartPacket;
import com.enviouse.futureshopsp.network.packets.C2SVerifyCartPacket;
import com.enviouse.futureshopsp.network.packets.S2CBalanceUiPacket;
import com.enviouse.futureshopsp.network.packets.S2CBalTopUiPacket;
import com.enviouse.futureshopsp.network.packets.S2CBarterResponsePacket;
import com.enviouse.futureshopsp.network.packets.S2CBuyResponsePacket;
import com.enviouse.futureshopsp.network.packets.S2CDepartmentListPacket;
import com.enviouse.futureshopsp.network.packets.S2CForceClosePacket;
import com.enviouse.futureshopsp.network.packets.S2CFranchiseDataPacket;
import com.enviouse.futureshopsp.network.packets.S2CHistoryResponsePacket;
import com.enviouse.futureshopsp.network.packets.S2CInventorySyncPacket;
import com.enviouse.futureshopsp.network.packets.S2CLocalShopsPacket;
import com.enviouse.futureshopsp.network.packets.S2CPlayerShopDataPacket;
import com.enviouse.futureshopsp.network.packets.S2CPlayerShopResultPacket;
import com.enviouse.futureshopsp.network.packets.S2CSellResponsePacket;
import com.enviouse.futureshopsp.network.packets.S2CSettlementHistoryPacket;
import com.enviouse.futureshopsp.network.packets.S2CShopDataPacket;
import com.enviouse.futureshopsp.network.packets.S2CVerifyCartResponsePacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge payload registration (replaces the Forge SimpleChannel). 37 payloads:
 * 21 C2S (playToServer) + 16 S2C (playToClient), versioned at PROTOCOL_VERSION so a
 * client whose futureshops:main protocol differs is refused at the configuration phase
 * (this preserves the anti-desync guarantee the dropped HandshakeHandlerMixin used to message).
 */
@EventBusSubscriber(modid = Futureshops.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ShopPackets {
    public static final String PROTOCOL_VERSION = "26";

    private ShopPackets() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(Futureshops.MODID).versioned(PROTOCOL_VERSION);
        registrar.playToServer(C2SBarterRequestPacket.TYPE, C2SBarterRequestPacket.STREAM_CODEC, C2SBarterRequestPacket::handle);
        registrar.playToServer(C2SBuyRequestPacket.TYPE, C2SBuyRequestPacket.STREAM_CODEC, C2SBuyRequestPacket::handle);
        registrar.playToServer(C2SFetchDepartmentsPacket.TYPE, C2SFetchDepartmentsPacket.STREAM_CODEC, C2SFetchDepartmentsPacket::handle);
        registrar.playToServer(C2SFetchHistoryPacket.TYPE, C2SFetchHistoryPacket.STREAM_CODEC, C2SFetchHistoryPacket::handle);
        registrar.playToServer(C2SFetchLocalShopsPacket.TYPE, C2SFetchLocalShopsPacket.STREAM_CODEC, C2SFetchLocalShopsPacket::handle);
        registrar.playToServer(C2SFetchSettlementHistoryPacket.TYPE, C2SFetchSettlementHistoryPacket.STREAM_CODEC, C2SFetchSettlementHistoryPacket::handle);
        registrar.playToServer(C2SFranchiseActionPacket.TYPE, C2SFranchiseActionPacket.STREAM_CODEC, C2SFranchiseActionPacket::handle);
        registrar.playToServer(C2SInventorySyncPacket.TYPE, C2SInventorySyncPacket.STREAM_CODEC, C2SInventorySyncPacket::handle);
        registrar.playToServer(C2SOpenBalanceUiPacket.TYPE, C2SOpenBalanceUiPacket.STREAM_CODEC, C2SOpenBalanceUiPacket::handle);
        registrar.playToServer(C2SOpenBalTopUiPacket.TYPE, C2SOpenBalTopUiPacket.STREAM_CODEC, C2SOpenBalTopUiPacket::handle);
        registrar.playToServer(C2SOpenShopPacket.TYPE, C2SOpenShopPacket.STREAM_CODEC, C2SOpenShopPacket::handle);
        registrar.playToServer(C2SPlayerShopActionPacket.TYPE, C2SPlayerShopActionPacket.STREAM_CODEC, C2SPlayerShopActionPacket::handle);
        registrar.playToServer(C2SPlayerShopBuybackConfigPacket.TYPE, C2SPlayerShopBuybackConfigPacket.STREAM_CODEC, C2SPlayerShopBuybackConfigPacket::handle);
        registrar.playToServer(C2SPlayerShopBuyPacket.TYPE, C2SPlayerShopBuyPacket.STREAM_CODEC, C2SPlayerShopBuyPacket::handle);
        registrar.playToServer(C2SPlayerShopConfigPacket.TYPE, C2SPlayerShopConfigPacket.STREAM_CODEC, C2SPlayerShopConfigPacket::handle);
        registrar.playToServer(C2SPlayerShopPromoPacket.TYPE, C2SPlayerShopPromoPacket.STREAM_CODEC, C2SPlayerShopPromoPacket::handle);
        registrar.playToServer(C2SPlayerShopSellPacket.TYPE, C2SPlayerShopSellPacket.STREAM_CODEC, C2SPlayerShopSellPacket::handle);
        registrar.playToServer(C2SSellRequestPacket.TYPE, C2SSellRequestPacket.STREAM_CODEC, C2SSellRequestPacket::handle);
        registrar.playToServer(C2SSetDepartmentPacket.TYPE, C2SSetDepartmentPacket.STREAM_CODEC, C2SSetDepartmentPacket::handle);
        registrar.playToServer(C2SVerifyAdminCartPacket.TYPE, C2SVerifyAdminCartPacket.STREAM_CODEC, C2SVerifyAdminCartPacket::handle);
        registrar.playToServer(C2SVerifyCartPacket.TYPE, C2SVerifyCartPacket.STREAM_CODEC, C2SVerifyCartPacket::handle);
        registrar.playToClient(S2CBalanceUiPacket.TYPE, S2CBalanceUiPacket.STREAM_CODEC, S2CBalanceUiPacket::handle);
        registrar.playToClient(S2CBalTopUiPacket.TYPE, S2CBalTopUiPacket.STREAM_CODEC, S2CBalTopUiPacket::handle);
        registrar.playToClient(S2CBarterResponsePacket.TYPE, S2CBarterResponsePacket.STREAM_CODEC, S2CBarterResponsePacket::handle);
        registrar.playToClient(S2CBuyResponsePacket.TYPE, S2CBuyResponsePacket.STREAM_CODEC, S2CBuyResponsePacket::handle);
        registrar.playToClient(S2CDepartmentListPacket.TYPE, S2CDepartmentListPacket.STREAM_CODEC, S2CDepartmentListPacket::handle);
        registrar.playToClient(S2CForceClosePacket.TYPE, S2CForceClosePacket.STREAM_CODEC, S2CForceClosePacket::handle);
        registrar.playToClient(S2CFranchiseDataPacket.TYPE, S2CFranchiseDataPacket.STREAM_CODEC, S2CFranchiseDataPacket::handle);
        registrar.playToClient(S2CHistoryResponsePacket.TYPE, S2CHistoryResponsePacket.STREAM_CODEC, S2CHistoryResponsePacket::handle);
        registrar.playToClient(S2CInventorySyncPacket.TYPE, S2CInventorySyncPacket.STREAM_CODEC, S2CInventorySyncPacket::handle);
        registrar.playToClient(S2CLocalShopsPacket.TYPE, S2CLocalShopsPacket.STREAM_CODEC, S2CLocalShopsPacket::handle);
        registrar.playToClient(S2CPlayerShopDataPacket.TYPE, S2CPlayerShopDataPacket.STREAM_CODEC, S2CPlayerShopDataPacket::handle);
        registrar.playToClient(S2CPlayerShopResultPacket.TYPE, S2CPlayerShopResultPacket.STREAM_CODEC, S2CPlayerShopResultPacket::handle);
        registrar.playToClient(S2CSellResponsePacket.TYPE, S2CSellResponsePacket.STREAM_CODEC, S2CSellResponsePacket::handle);
        registrar.playToClient(S2CSettlementHistoryPacket.TYPE, S2CSettlementHistoryPacket.STREAM_CODEC, S2CSettlementHistoryPacket::handle);
        registrar.playToClient(S2CShopDataPacket.TYPE, S2CShopDataPacket.STREAM_CODEC, S2CShopDataPacket::handle);
        registrar.playToClient(S2CVerifyCartResponsePacket.TYPE, S2CVerifyCartResponsePacket.STREAM_CODEC, S2CVerifyCartResponsePacket::handle);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
