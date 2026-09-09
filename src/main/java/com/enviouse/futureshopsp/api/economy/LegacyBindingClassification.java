package com.enviouse.futureshopsp.api.economy;

/** Explicit classification for records written before immutable account binding existed. */
public enum LegacyBindingClassification {
    LEGACY_COMPATIBLE,
    LEGACY_HYBRID_UNRESOLVED,
    LEGACY_UNPROVABLE
}
