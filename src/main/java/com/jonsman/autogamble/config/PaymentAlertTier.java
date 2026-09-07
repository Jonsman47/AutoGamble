package com.jonsman.autogamble.config;

import java.math.BigDecimal;

/** One independently configurable payment-alert tier. */
public final class PaymentAlertTier {
    public boolean enabled;
    public BigDecimal threshold;
    public String sound;
    public float volume;
    public float pitch;

    public PaymentAlertTier() {}
    public PaymentAlertTier(boolean enabled, String threshold, String sound, float volume, float pitch) {
        this.enabled = enabled; this.threshold = new BigDecimal(threshold); this.sound = sound;
        this.volume = volume; this.pitch = pitch;
    }
    public PaymentAlertTier copy() {
        PaymentAlertTier result = new PaymentAlertTier();
        result.enabled = enabled; result.threshold = threshold; result.sound = sound;
        result.volume = volume; result.pitch = pitch;
        return result;
    }
}
