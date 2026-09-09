package com.enviouse.futureshopsp.server.economy;

import com.enviouse.futureshopsp.api.economy.EconomyApi;
import com.enviouse.futureshopsp.api.economy.LegacyBindingClassification;

import java.util.Objects;

/** Classifies records written before account binding was persisted. */
public final class LegacyBindingClassifier {
    private LegacyBindingClassifier() {
    }

    public static LegacyBindingClassification classify(EconomyJournalRecord record) {
        Objects.requireNonNull(record, "record");
        String provider = record.providerId();
        if (provider.isBlank() || EconomyApi.INTERNAL_PROVIDER_ID.equals(provider)
                || EconomyApi.DANCONOMY_PROVIDER_ID.equals(provider)
                || EconomyApi.VAULT_PROVIDER_ID.equals(provider)) {
            return LegacyBindingClassification.LEGACY_COMPATIBLE;
        }
        if (EconomyApi.PIXELMON_PROVIDER_ID.equals(provider)) {
            return LegacyBindingClassification.LEGACY_HYBRID_UNRESOLVED;
        }
        return LegacyBindingClassification.LEGACY_UNPROVABLE;
    }
}
