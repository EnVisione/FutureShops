package com.enviouse.futureshopsp.api.economy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Public operation request used by the versioned binding seam. */
public record OperationRequest(
        RequestId requestId,
        UUID actor,
        Optional<UUID> counterparty,
        long amountMinorUnits,
        MutationKind kind,
        String operation) {

    public OperationRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(actor, "actor");
        counterparty = counterparty == null ? Optional.empty() : counterparty;
        Objects.requireNonNull(kind, "kind");
        if (amountMinorUnits <= 0L) {
            throw new IllegalArgumentException("amountMinorUnits must be positive");
        }
        operation = operation == null || operation.isBlank() ? kind.name().toLowerCase(java.util.Locale.ROOT) : operation;
        if (operation.length() > 64 || operation.indexOf('\n') >= 0 || operation.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("operation must be a bounded single line");
        }
    }

    public MutationRequest mutationRequest() {
        return new MutationRequest(requestId, actor, counterparty, amountMinorUnits, kind);
    }

    public static OperationRequest from(MutationRequest request) {
        Objects.requireNonNull(request, "request");
        return new OperationRequest(request.requestId(), request.actor(), request.counterparty(),
                request.amountMinorUnits(), request.kind(), request.kind().name().toLowerCase(java.util.Locale.ROOT));
    }
}
