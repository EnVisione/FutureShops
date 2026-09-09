package com.enviouse.futureshopsp.api.economy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable, serializable identity for the account used by one economy operation. */
public record PersistedAccountBindingV1(
        int bindingSchema,
        String providerId,
        int providerApiVersion,
        String adapterId,
        String adapterProtocol,
        String backendClassName,
        String backendSha256,
        Optional<String> backendSha512,
        String durableBackendLineage,
        UUID accountUuid,
        String currencyId,
        int currencyPrecision,
        String durableManagerIdentity,
        long bindingGeneration,
        RequestId rootRequestId,
        RequestId legRequestId,
        String requestFingerprint,
        int receiptProtocolVersion) {

    public static final int SCHEMA_VERSION = 1;

    public PersistedAccountBindingV1 {
        if (bindingSchema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported account binding schema");
        }
        requireToken(providerId, "providerId", 64);
        if (!EconomyApi.isValidProviderId(providerId)) {
            throw new IllegalArgumentException("providerId is invalid");
        }
        if (providerApiVersion < 1 || providerApiVersion > 32) {
            throw new IllegalArgumentException("providerApiVersion is outside the supported range");
        }
        requireToken(adapterId, "adapterId", 128);
        requireToken(adapterProtocol, "adapterProtocol", 64);
        requireToken(backendClassName, "backendClassName", 256);
        requireFingerprint(backendSha256, "backendSha256", 64);
        backendSha512 = backendSha512 == null ? Optional.empty() : backendSha512;
        backendSha512.ifPresent(value -> requireFingerprint(value, "backendSha512", 128));
        requireToken(durableBackendLineage, "durableBackendLineage", 512);
        Objects.requireNonNull(accountUuid, "accountUuid");
        requireToken(currencyId, "currencyId", 128);
        if (currencyPrecision < 0 || currencyPrecision > 18) {
            throw new IllegalArgumentException("currencyPrecision is outside the supported range");
        }
        requireToken(durableManagerIdentity, "durableManagerIdentity", 512);
        if (bindingGeneration < 0L) {
            throw new IllegalArgumentException("bindingGeneration must not be negative");
        }
        Objects.requireNonNull(rootRequestId, "rootRequestId");
        Objects.requireNonNull(legRequestId, "legRequestId");
        requireToken(requestFingerprint, "requestFingerprint", 128);
        requireFingerprint(requestFingerprint, "requestFingerprint", 64);
        if (receiptProtocolVersion < 1 || receiptProtocolVersion > 32) {
            throw new IllegalArgumentException("receiptProtocolVersion is outside the supported range");
        }
    }

    public PersistedAccountBindingV1(String providerId,
                                     int providerApiVersion,
                                     String adapterId,
                                     String adapterProtocol,
                                     String backendClassName,
                                     String backendSha256,
                                     Optional<String> backendSha512,
                                     String durableBackendLineage,
                                     UUID accountUuid,
                                     String currencyId,
                                     int currencyPrecision,
                                     String durableManagerIdentity,
                                     long bindingGeneration,
                                     RequestId rootRequestId,
                                     RequestId legRequestId,
                                     String requestFingerprint,
                                     int receiptProtocolVersion) {
        this(SCHEMA_VERSION, providerId, providerApiVersion, adapterId, adapterProtocol,
                backendClassName, backendSha256, backendSha512, durableBackendLineage, accountUuid,
                currencyId, currencyPrecision, durableManagerIdentity, bindingGeneration,
                rootRequestId, legRequestId, requestFingerprint, receiptProtocolVersion);
    }

    private static void requireToken(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maxLength || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " must be a bounded single line");
        }
    }

    private static void requireFingerprint(String value, String field, int maxLength) {
        requireToken(value, field, maxLength);
        if (value.length() != 64 && value.length() != 128
                || !value.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException(field + " must be a hexadecimal fingerprint");
        }
    }
}
