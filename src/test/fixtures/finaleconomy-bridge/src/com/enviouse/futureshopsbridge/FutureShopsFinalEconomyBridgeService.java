package com.enviouse.futureshopsbridge;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public interface FutureShopsFinalEconomyBridgeService {
    Map<String, Object> balance(Object account, UUID actor);

    Map<String, Object> precheck(Object account, UUID actor, long amount, String kind);

    Map<String, Object> mutate(Object account, UUID actor, UUID requestId, long amount, String kind);

    Map<String, Object> lookup(Object account, UUID actor, UUID requestId, long amount, String kind);

    static Map<String, Object> confirmedBalance(long balance) {
        return Map.of("status", "CONFIRMED", "error", "NONE", "balance", balance, "diagnostic", "");
    }

    static Map<String, Object> confirmedReceipt(UUID requestId, UUID actor, String kind, long amount,
                                                long balance) {
        return Map.of("status", "CONFIRMED", "error", "NONE", "request_id", requestId.toString(),
                "actor", actor.toString(), "kind", kind, "amount", amount,
                "external_operation_id", "finaleconomy:" + requestId, "balance", balance, "diagnostic", "");
    }

    static Map<String, Object> rejected(String error, String diagnostic) {
        return Map.of("status", "REJECTED", "error", error, "diagnostic", diagnostic);
    }

    static Map<String, Object> unavailable(String diagnostic) {
        return Map.of("status", "UNAVAILABLE", "error", "PROVIDER_EXCEPTION", "diagnostic", diagnostic);
    }

    static Map<String, Object> ambiguous(String diagnostic) {
        return Map.of("status", "AMBIGUOUS", "error", "UNKNOWN", "diagnostic", diagnostic);
    }
}
