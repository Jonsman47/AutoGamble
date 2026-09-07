package com.jonsman.autogamble.manager;

import com.jonsman.autogamble.config.AutoGambleConfig;
import com.jonsman.autogamble.config.PaymentAlertTier;
import java.math.BigDecimal;
import java.util.Optional;

/** Selects the single highest matching tier and rate-limits local playback. */
public final class PaymentSoundAlerts {
    private long lastPlayedMs = Long.MIN_VALUE;

    public Optional<PaymentAlertTier> select(BigDecimal amount, long nowMs, AutoGambleConfig config) {
        if (!config.paymentSoundAlertsEnabled || amount == null || amount.signum() < 0) return Optional.empty();
        if (lastPlayedMs != Long.MIN_VALUE && nowMs - lastPlayedMs < config.minimumAlertSpacingMs) return Optional.empty();
        PaymentAlertTier selected = null;
        for (PaymentAlertTier tier : config.paymentAlertTiers) {
            if (tier.enabled && amount.compareTo(tier.threshold) >= 0
                    && (selected == null || tier.threshold.compareTo(selected.threshold) > 0)) selected = tier;
        }
        if (selected != null) lastPlayedMs = nowMs;
        return Optional.ofNullable(selected);
    }

    public void resetSession() { lastPlayedMs = Long.MIN_VALUE; }
}
