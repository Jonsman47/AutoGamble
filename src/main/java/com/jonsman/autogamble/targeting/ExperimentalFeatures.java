package com.jonsman.autogamble.targeting;

/** Preserves experimental code and stored preferences without activating it in production. */
public final class ExperimentalFeatures {
    public static final boolean BALTOP_PAYMENT_FEATURE_ENABLED = false;
    public static final String UNAVAILABLE_MESSAGE = "Coming soon — baltop payment targeting is currently unavailable.";
    private ExperimentalFeatures() {}
}
