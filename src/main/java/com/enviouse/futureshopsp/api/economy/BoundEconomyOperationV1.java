package com.enviouse.futureshopsp.api.economy;

import java.util.Objects;
import java.util.UUID;

/** Immutable operation carrying one persisted account binding through every coordinator seam. */
public record BoundEconomyOperationV1(
        MutationRequest request,
        UUID actorId,
        ProviderCapabilities requiredCapabilities,
        PersistedAccountBindingV1 persistedBinding,
        RuntimeBindingProofV1 runtimeProof) {

    public BoundEconomyOperationV1 {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        Objects.requireNonNull(persistedBinding, "persistedBinding");
        Objects.requireNonNull(runtimeProof, "runtimeProof");
        if (!actorId.equals(request.actor()) || !actorId.equals(persistedBinding.accountUuid())) {
            throw new IllegalArgumentException("operation actor does not match bound account");
        }
        if (!request.requestId().equals(persistedBinding.legRequestId())) {
            throw new IllegalArgumentException("operation request does not match bound leg");
        }
    }

    public PersistedAccountBindingV1 binding() {
        return persistedBinding;
    }

    public RuntimeBindingProofV1 proof() {
        return runtimeProof;
    }
}
