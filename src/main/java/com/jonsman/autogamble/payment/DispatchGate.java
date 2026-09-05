package com.jonsman.autogamble.payment;

/** Shared by advertising and payouts; a reserved attempt consumes the tick even if the API fails. */
public final class DispatchGate {
    private boolean used = true;
    public void beginTick() { used = false; }
    public boolean reserve() { if (used) return false; used = true; return true; }
}
