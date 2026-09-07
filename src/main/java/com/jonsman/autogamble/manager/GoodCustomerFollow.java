package com.jonsman.autogamble.manager;

import com.jonsman.autogamble.config.AutoGambleConfig;
import com.jonsman.autogamble.history.PaymentHistory;
import com.jonsman.autogamble.payment.PaymentSender;
import java.util.*;
import java.util.function.Function;
import org.slf4j.LoggerFactory;

/** Client-thread command decisions; durable identity belongs to PaymentHistory's worker. */
public final class GoodCustomerFollow {
    private final PaymentHistory history;
    private final Set<String> attempted = new HashSet<>();
    private final Map<String, Long> simulated = new HashMap<>();
    private int cursor;
    public GoodCustomerFollow(PaymentHistory history) { this.history = history; }
    public void tick(AutoGambleConfig c, long now, Function<String, PaymentSender.Result> sender) {
        if (!c.enabled || !c.autoFollowGoodCustomersEnabled) return;
        var state = history.snapshot(); if (!state.ready() || state.eligible().isEmpty()) return;
        var customer = state.eligible().get(Math.floorMod(cursor++, state.eligible().size()));
        String key = PaymentHistory.key(customer.name());
        if (customer.total().compareTo(c.autoFollowThreshold) < 0 || attempted.contains(key) || state.followed().contains(key)) return;
        if (c.dryRunMode) {
            Long last = simulated.get(key);
            if (last == null || now - last >= 30_000_000_000L) {
                LoggerFactory.getLogger("autogamble").info("[AutoGamble] DRY RUN: Would follow {}", customer.name());
                simulated.put(key, now);
            }
            return;
        }
        PaymentSender.Result result = sender.apply(customer.name());
        if (result != PaymentSender.Result.RETRY_LATER) attempted.add(key);
        if (result == PaymentSender.Result.SENT) history.followed(customer.name());
    }
    public void clearHistory() { attempted.clear(); simulated.clear(); cursor = 0; history.clearFollowed(); }
    public void resetSession() { simulated.clear(); } // Real attempts survive reconnects and server switches.
}
