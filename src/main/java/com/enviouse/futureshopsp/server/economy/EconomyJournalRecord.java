package com.enviouse.futureshopsp.server.economy;

import com.enviouse.futureshopsp.api.economy.MutationReceipt;
import com.enviouse.futureshopsp.api.economy.MutationRequest;
import com.enviouse.futureshopsp.api.economy.PersistedAccountBindingV1;
import com.enviouse.futureshopsp.api.economy.ProviderResult;
import com.enviouse.futureshopsp.api.economy.ProviderResultStatus;

import java.util.Objects;
import java.util.Optional;

/** Immutable journal entry with no external balance field. */
public record EconomyJournalRecord(
        MutationRequest request,
        EconomyTransactionState state,
        Optional<MutationReceipt> receipt,
        ProviderResultStatus resultStatus,
        String diagnostic,
        String providerId,
        Optional<PersistedAccountBindingV1> binding) {
    public EconomyJournalRecord(MutationRequest request, EconomyTransactionState state,
                                Optional<MutationReceipt> receipt, ProviderResultStatus resultStatus,
                                String diagnostic) {
        this(request, state, receipt, resultStatus, diagnostic, "", Optional.empty());
    }

    public EconomyJournalRecord(MutationRequest request, EconomyTransactionState state,
                                Optional<MutationReceipt> receipt, ProviderResultStatus resultStatus,
                                String diagnostic, String providerId) {
        this(request, state, receipt, resultStatus, diagnostic, providerId, Optional.empty());
    }

    public EconomyJournalRecord {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(resultStatus, "resultStatus");
        diagnostic = diagnostic == null ? "" : diagnostic;
        providerId = providerId == null ? "" : providerId;
        binding = binding == null ? Optional.empty() : binding;
        if (providerId.length() > 64 || providerId.indexOf('\n') >= 0 || providerId.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("providerId must be a bounded single line");
        }
        if (diagnostic.length() > 256 || diagnostic.indexOf('\n') >= 0 || diagnostic.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("diagnostic must be a single line of at most 256 characters");
        }
        if (binding.isPresent()) {
            PersistedAccountBindingV1 value = binding.orElseThrow();
            if (!value.accountUuid().equals(request.actor())
                    || !value.rootRequestId().equals(request.requestId())
                    || !value.legRequestId().equals(request.requestId())) {
                throw new IllegalArgumentException("journal binding does not match request");
            }
            if (!providerId.isBlank() && !providerId.equals(value.providerId())) {
                throw new IllegalArgumentException("journal binding does not match provider");
            }
        }
    }

    public EconomyJournalRecord withBinding(PersistedAccountBindingV1 value) {
        return new EconomyJournalRecord(request, state, receipt, resultStatus, diagnostic, providerId,
                Optional.ofNullable(value));
    }

    public boolean incomplete() {
        return state != EconomyTransactionState.RESOLVED
                && state != EconomyTransactionState.DELIVERED
                && state != EconomyTransactionState.CLAIMED;
    }
}
