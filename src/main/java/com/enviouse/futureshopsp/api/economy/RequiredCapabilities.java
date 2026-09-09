package com.enviouse.futureshopsp.api.economy;

import java.util.Objects;

/** Named wrapper used by account binding entrypoints to distinguish required capabilities. */
public record RequiredCapabilities(ProviderCapabilities value) {
    public RequiredCapabilities {
        Objects.requireNonNull(value, "value");
    }

    public static RequiredCapabilities all() {
        return new RequiredCapabilities(ProviderCapabilities.all());
    }

    public static RequiredCapabilities none() {
        return new RequiredCapabilities(ProviderCapabilities.none());
    }

    public ProviderCapabilities capabilities() {
        return value;
    }
}
