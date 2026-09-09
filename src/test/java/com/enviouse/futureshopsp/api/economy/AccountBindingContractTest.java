package com.enviouse.futureshopsp.api.economy;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountBindingContractTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final RequestId ROOT = new RequestId(UUID.fromString("00000000-0000-0000-0000-000000000022"));
    private static final RequestId LEG = new RequestId(UUID.fromString("00000000-0000-0000-0000-000000000023"));

    @Test
    void codecRoundTripPreservesEveryPersistedField() {
        PersistedAccountBindingV1 binding = binding();

        PersistedAccountBindingV1 decoded = AccountBindingCodecV1.decode(AccountBindingCodecV1.encode(binding));

        assertEquals(binding, decoded);
    }

    @Test
    void codecRejectsTamperedAndTrailingPayloads() {
        String encoded = AccountBindingCodecV1.encode(binding());
        String tampered = encoded.substring(0, encoded.length() - 1)
                + (encoded.charAt(encoded.length() - 1) == 'A' ? 'B' : 'A');

        assertThrows(IllegalArgumentException.class, () -> AccountBindingCodecV1.decode(tampered));
        assertThrows(IllegalArgumentException.class, () -> AccountBindingCodecV1.decode(encoded + "A"));
    }

    @Test
    void boundOperationRequiresMatchingActorAndLegIdentity() {
        RuntimeBindingProofV1 proof = new RuntimeBindingProofV1(
                "example.Account", "loader-1", Set.of("example.Account#getBalance"),
                new Object(), new Object(), new Object(), 4L, ProviderCapabilities.all(), "");
        MutationRequest request = new MutationRequest(LEG, ACTOR, Optional.empty(), 10L, MutationKind.WITHDRAW);
        BoundEconomyOperationV1 operation = new BoundEconomyOperationV1(
                request, ACTOR, ProviderCapabilities.all(), binding(), proof);

        assertEquals(ACTOR, operation.actorId());
        assertTrue(operation.runtimeProof().valid());
        assertThrows(IllegalArgumentException.class, () -> new BoundEconomyOperationV1(
                request, UUID.randomUUID(), ProviderCapabilities.all(), binding(), proof));
    }

    private static PersistedAccountBindingV1 binding() {
        return new PersistedAccountBindingV1(
                "pixelmon", 1, "pixelmon", "pixelmon-native-v1", "example.Backend",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                Optional.of("ab".repeat(64)),
                "backend-lineage", ACTOR, "poke_dollar", 0, "manager-lineage", 2L,
                ROOT, LEG,
                "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", 1);
    }
}
