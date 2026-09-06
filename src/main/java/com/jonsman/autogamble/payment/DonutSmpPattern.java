package com.jonsman.autogamble.payment;

/** Incoming wording confirmed by user-supplied authentic examples. No outgoing/error pattern is assumed. */
public final class DonutSmpPattern {
    private DonutSmpPattern() {}
    public static final String REGEX = "^(?<sender>[A-Za-z0-9_]{3,16})\\s+paid\\s+you\\s+\\$\\s*(?<amount>[0-9][0-9,]*(?:\\.[0-9]{1,2})?[kKmMbBtT]?)$";
}
