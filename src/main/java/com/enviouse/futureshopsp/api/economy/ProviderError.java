package com.enviouse.futureshopsp.api.economy;

/** Stable reason values for non-confirmed provider results. */
public enum ProviderError {
    NONE,
    INVALID_REQUEST,
    INVALID_AMOUNT,
    INSUFFICIENT_FUNDS,
    CAPABILITY_MISSING,
    NOT_READY,
    INCOMPATIBLE,
    PERMISSION_DENIED,
    DUPLICATE_REQUEST,
    RECEIPT_NOT_FOUND,
    PROVIDER_EXCEPTION,
    TIMEOUT,
    REQUEST_CONFLICT,
    BINDING_CHANGED,
    INVALID_PRECISION,
    UNKNOWN
}
