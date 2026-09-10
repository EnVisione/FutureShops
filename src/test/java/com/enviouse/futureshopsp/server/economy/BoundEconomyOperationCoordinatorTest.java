package com.enviouse.futureshopsp.server.economy;

import com.enviouse.futureshopsp.api.economy.BoundEconomyOperationV1;
import com.enviouse.futureshopsp.api.economy.BoundEconomyProvider;
import com.enviouse.futureshopsp.api.economy.BalanceSnapshot;
import com.enviouse.futureshopsp.api.economy.CurrencyMetadata;
import com.enviouse.futureshopsp.api.economy.EconomyApi;
import com.enviouse.futureshopsp.api.economy.EconomyCapability;
import com.enviouse.futureshopsp.api.economy.EconomyProvider;
import com.enviouse.futureshopsp.api.economy.MutationKind;
import com.enviouse.futureshopsp.api.economy.MutationReceipt;
import com.enviouse.futureshopsp.api.economy.MutationRequest;
import com.enviouse.futureshopsp.api.economy.OperationRequest;
import com.enviouse.futureshopsp.api.economy.PersistedAccountBindingV1;
import com.enviouse.futureshopsp.api.economy.ProviderCapabilities;
import com.enviouse.futureshopsp.api.economy.ProviderError;
import com.enviouse.futureshopsp.api.economy.ProviderLifecycle;
import com.enviouse.futureshopsp.api.economy.ProviderReadiness;
import com.enviouse.futureshopsp.api.economy.ProviderResult;
import com.enviouse.futureshopsp.api.economy.RequestId;
import com.enviouse.futureshopsp.api.economy.RequiredCapabilities;
import com.enviouse.futureshopsp.api.economy.RuntimeBindingProofV1;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundEconomyOperationCoordinatorTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000031");

    @Test
    void bindingBarrierRunsBeforePrecheckAndMutation() {
        BoundProvider provider = new BoundProvider();
        EconomyLifecycleController lifecycle = new EconomyLifecycleController(provider.providerId());
        lifecycle.resolve(ProviderLifecycle.READY, "", true, true, false);
        EconomyTransactionCoordinator coordinator = new EconomyTransactionCoordinator(provider, lifecycle,
                new InMemoryEconomyTransactionJournal());
        MutationRequest request = MutationRequest.forPlayer(RequestId.random(), ACTOR, 10L, MutationKind.WITHDRAW);
        BoundEconomyOperationV1 operation = operation(request, ProviderCapabilities.all(), 1L);
        coordinator.bind(operation);

        assertTrue(coordinator.preflight(operation).confirmed());
        assertTrue(coordinator.withdraw(operation).confirmed());
        assertEquals(1, provider.boundMutateCalls);
        assertEquals(0, provider.withdrawCalls);
    }

    @Test
    void bindingReplacementAndMissingRuntimeProofFailClosed() {
        BoundProvider provider = new BoundProvider();
        EconomyLifecycleController lifecycle = new EconomyLifecycleController(provider.providerId());
        lifecycle.resolve(ProviderLifecycle.READY, "", true, true, false);
        EconomyTransactionCoordinator coordinator = new EconomyTransactionCoordinator(provider, lifecycle,
                new InMemoryEconomyTransactionJournal());
        RequestId requestId = RequestId.random();
        MutationRequest request = MutationRequest.forPlayer(requestId, ACTOR, 10L, MutationKind.WITHDRAW);
        BoundEconomyOperationV1 original = operation(request, ProviderCapabilities.all(), 1L);
        coordinator.bind(original);
        BoundEconomyOperationV1 changed = operation(request, ProviderCapabilities.all(), 2L);

        assertThrows(IllegalStateException.class, () -> coordinator.bind(changed));
        assertEquals(ProviderError.BINDING_CHANGED, coordinator.preflight(changed).error());

        MutationRequest unprovedMutation = MutationRequest.forPlayer(RequestId.random(), ACTOR, 10L,
                MutationKind.WITHDRAW);
        OperationRequest unprovedRequest = OperationRequest.from(unprovedMutation);
        BoundEconomyOperationV1 unproved = coordinator.bind(unprovedRequest, ACTOR,
                new RequiredCapabilities(ProviderCapabilities.all()));
        assertEquals(ProviderError.CAPABILITY_MISSING, coordinator.preflight(unproved).error());
        assertEquals(0, provider.withdrawCalls);
    }

    @Test
    void regularRequestUsesBoundRouteAndPersistsBinding() {
        BoundProvider provider = new BoundProvider();
        EconomyLifecycleController lifecycle = new EconomyLifecycleController(provider.providerId());
        lifecycle.resolve(ProviderLifecycle.READY, "", true, true, false);
        InMemoryEconomyTransactionJournal journal = new InMemoryEconomyTransactionJournal();
        EconomyTransactionCoordinator coordinator = new EconomyTransactionCoordinator(provider, lifecycle, journal);
        MutationRequest request = MutationRequest.forPlayer(RequestId.random(), ACTOR, 10L,
                MutationKind.WITHDRAW);

        ProviderResult<MutationReceipt> result = coordinator.withdraw(request);

        assertTrue(result.confirmed());
        assertEquals(1, provider.bindCalls);
        assertEquals(1, provider.boundPrecheckCalls);
        assertEquals(1, provider.boundMutateCalls);
        assertEquals(0, provider.withdrawCalls);
        assertTrue(journal.find(request.requestId()).orElseThrow().binding().isPresent());
    }

    private static BoundEconomyOperationV1 operation(MutationRequest request,
                                                      ProviderCapabilities required,
                                                      long generation) {
        PersistedAccountBindingV1 persisted = new PersistedAccountBindingV1(
                "fixture", EconomyApi.COMPATIBILITY_VERSION, "fixture", "fixture-v1",
                BoundProvider.class.getName(),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                Optional.empty(), "fixture-lineage", ACTOR, "Coin", 2, "fixture-manager",
                generation, request.requestId(), request.requestId(),
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", 1);
        RuntimeBindingProofV1 proof = new RuntimeBindingProofV1(
                BoundProvider.class.getName(), "fixture-loader", Set.of("fixture#getBalance"),
                new Object(), new Object(), new Object(), generation, ProviderCapabilities.all(), "");
        return new BoundEconomyOperationV1(request, ACTOR, required, persisted, proof);
    }

    private static final class BoundProvider implements EconomyProvider, BoundEconomyProvider {
        private int bindCalls;
        private int boundPrecheckCalls;
        private int boundMutateCalls;
        private int withdrawCalls;

        @Override
        public ProviderResult<BoundEconomyOperationV1> bind(OperationRequest request, RequiredCapabilities required) {
            bindCalls++;
            return ProviderResult.confirmed(operation(request.mutationRequest(), required.value(), 1L));
        }

        @Override
        public ProviderResult<BalanceSnapshot> precheck(BoundEconomyOperationV1 operation) {
            boundPrecheckCalls++;
            return ProviderResult.confirmed(new BalanceSnapshot(operation.actorId(), 100L));
        }

        @Override
        public ProviderResult<MutationReceipt> mutate(BoundEconomyOperationV1 operation, MutationRequest request) {
            boundMutateCalls++;
            return ProviderResult.confirmed(new MutationReceipt(request.requestId(), request.kind(),
                    request.amountMinorUnits(), "bound-" + request.requestId().value(), OptionalLong.of(90L)));
        }

        @Override
        public ProviderResult<MutationReceipt> lookup(BoundEconomyOperationV1 operation) {
            return ProviderResult.rejected(ProviderError.RECEIPT_NOT_FOUND, "missing");
        }

        @Override
        public ProviderResult<MutationReceipt> retry(BoundEconomyOperationV1 operation) {
            return mutate(operation, operation.request());
        }

        @Override
        public String providerId() {
            return "fixture";
        }

        @Override
        public int compatibilityVersion() {
            return EconomyApi.COMPATIBILITY_VERSION;
        }

        @Override
        public CurrencyMetadata currency() {
            return new CurrencyMetadata("Coin", "Coins", 2);
        }

        @Override
        public ProviderCapabilities capabilities() {
            return ProviderCapabilities.all();
        }

        @Override
        public ProviderReadiness readiness() {
            return new ProviderReadiness(ProviderLifecycle.READY, "");
        }

        @Override
        public ProviderResult<BalanceSnapshot> balance(UUID playerId) {
            return ProviderResult.confirmed(new BalanceSnapshot(playerId, 100L));
        }

        @Override
        public ProviderResult<BalanceSnapshot> precheck(MutationRequest request) {
            return ProviderResult.confirmed(new BalanceSnapshot(request.actor(), 100L));
        }

        @Override
        public ProviderResult<MutationReceipt> withdraw(MutationRequest request) {
            withdrawCalls++;
            return ProviderResult.confirmed(new MutationReceipt(request.requestId(), request.kind(),
                    request.amountMinorUnits(), "fixture-" + request.requestId().value(), OptionalLong.of(90L)));
        }

        @Override
        public ProviderResult<MutationReceipt> deposit(MutationRequest request) {
            return withdraw(request);
        }

        @Override
        public ProviderResult<MutationReceipt> lookup(RequestId requestId) {
            return ProviderResult.rejected(ProviderError.RECEIPT_NOT_FOUND, "missing");
        }

        @Override
        public ProviderResult<MutationReceipt> retry(MutationRequest request) {
            return withdraw(request);
        }
    }
}
