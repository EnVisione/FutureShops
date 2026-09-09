package com.enviouse.futureshopsp.api.economy;

import java.util.Objects;
import java.util.Set;

/** Runtime only proof for a persisted account binding. Object references never serialize. */
public record RuntimeBindingProofV1(
        String accountClass,
        String classLoaderIdentity,
        Set<String> verifiedDescriptors,
        Object accountWrapperReference,
        Object fePlayerDataAccessHandle,
        Object managerReference,
        long runtimeGeneration,
        ProviderCapabilities effectiveCapabilityProof,
        String invalidationReason) {

    public RuntimeBindingProofV1 {
        requireToken(accountClass, "accountClass", 256);
        requireToken(classLoaderIdentity, "classLoaderIdentity", 512);
        verifiedDescriptors = Set.copyOf(Objects.requireNonNull(verifiedDescriptors, "verifiedDescriptors"));
        if (verifiedDescriptors.size() > 128) {
            throw new IllegalArgumentException("verifiedDescriptors is too large");
        }
        verifiedDescriptors.forEach(value -> requireToken(value, "verified descriptor", 512));
        Objects.requireNonNull(effectiveCapabilityProof, "effectiveCapabilityProof");
        if (runtimeGeneration < 0L) {
            throw new IllegalArgumentException("runtimeGeneration must not be negative");
        }
        invalidationReason = invalidationReason == null ? "" : invalidationReason;
        if (invalidationReason.length() > 256 || invalidationReason.indexOf('\n') >= 0
                || invalidationReason.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalidationReason must be a bounded single line");
        }
    }

    public boolean valid() {
        return invalidationReason.isBlank();
    }

    public ProviderCapabilities accountObservedCapabilities() {
        return effectiveCapabilityProof;
    }

    private static void requireToken(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maxLength || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " must be a bounded single line");
        }
    }
}
