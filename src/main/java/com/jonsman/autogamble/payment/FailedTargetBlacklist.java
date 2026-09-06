package com.jonsman.autogamble.payment;
import java.util.*;

/** Session-only, conservative attribution of an unlabelled server error. */
public final class FailedTargetBlacklist {
    public static final long DURATION = 600_000_000_000L, RESPONSE_WINDOW = 3_000_000_000L;
    private final Map<String, Long> failed = new HashMap<>();
    private String lastTarget;
    private long lastAt;
    public void dispatched(String target, OutgoingPaymentTracker.Source source, long now) {
        lastTarget = source == OutgoingPaymentTracker.Source.ADVERTISING ? target : null;
        lastAt = now;
    }
    public Optional<String> receive(ReceivedMessage message, long now) {
        if (message.channel() == ReceivedMessage.Channel.PLAYER_CHAT || lastTarget == null
                || now - lastAt < 0 || now - lastAt > RESPONSE_WINDOW
                || !ReceivedMessage.normalize(message.text()).equals("That player does not exist")) return Optional.empty();
        String target = lastTarget; lastTarget = null;
        failed.put(target.toLowerCase(Locale.ROOT), now + DURATION);
        return Optional.of(target);
    }
    public boolean contains(String name, long now) { expire(now); return failed.containsKey(name.toLowerCase(Locale.ROOT)); }
    public int size(long now) { expire(now); return failed.size(); }
    private void expire(long now) { failed.values().removeIf(until -> now - until >= 0); }
    public void reset() { failed.clear(); lastTarget = null; }
    public void unrelatedCommand() { lastTarget = null; }
}
