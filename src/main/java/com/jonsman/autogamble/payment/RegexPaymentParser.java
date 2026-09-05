package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.config.AutoGambleConfig.IncomingPattern;
import java.util.*;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.LoggerFactory;

/** Explicit opt-in, full-message regex matches with named sender/amount groups. */
public final class RegexPaymentParser implements PaymentParser {
    private final List<Pattern> patterns = new ArrayList<>();
    public RegexPaymentParser(List<IncomingPattern> configured) {
        for (var entry : configured) {
            if (patterns.size() >= 16) break;
            if (entry == null || !entry.enabled || entry.regex == null || entry.regex.length() > 512) continue;
            try {
                Pattern p = Pattern.compile(entry.regex);
                if (!p.namedGroups().keySet().containsAll(Set.of("sender", "amount"))) throw new IllegalArgumentException("Missing named groups");
                patterns.add(p);
            } catch (IllegalArgumentException e) { LoggerFactory.getLogger("autogamble").warn("[AutoGamble] Invalid incoming pattern disabled: {}", e.getMessage()); }
        }
    }
    public int enabledCount() { return patterns.size(); }
    public Optional<IncomingPayment> parse(ReceivedMessage message, String local) {
        if (message == null || message.channel() == ReceivedMessage.Channel.PLAYER_CHAT) return Optional.empty();
        return parse(message.text(), local);
    }
    @Override public Optional<IncomingPayment> parse(String raw, String local) {
        if (raw == null || raw.length() > 1024) return Optional.empty();
        String text = ReceivedMessage.normalize(raw);
        // Bound work on the client thread even for a pathological user-supplied Java regex.
        Budget budget = new Budget();
        for (Pattern pattern : patterns) {
            try {
                var match = pattern.matcher(new LimitedText(text, 0, text.length(), budget));
                if (!match.matches()) continue;
                String sender = match.group("sender");
                if (sender == null || !sender.matches("[A-Za-z0-9_]{3,16}") || sender.equalsIgnoreCase(local)) continue;
                var amount = MoneyParser.parse(match.group("amount"));
                if (amount.isPresent()) return Optional.of(new IncomingPayment(sender, amount.get(), fingerprint(text)));
            } catch (BudgetExceeded | StackOverflowError e) { return Optional.empty(); }
        }
        return Optional.empty();
    }
    private static String fingerprint(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static final class Budget { int reads; final long start = System.nanoTime(); }
    private static final class BudgetExceeded extends RuntimeException {}
    private record LimitedText(String value, int start, int end, Budget budget) implements CharSequence {
        public int length() { return end - start; }
        public char charAt(int index) {
            if (++budget.reads > 50000 || System.nanoTime() - budget.start > 5_000_000) throw new BudgetExceeded();
            return value.charAt(start + index);
        }
        public CharSequence subSequence(int from, int to) { return new LimitedText(value, start + from, start + to, budget); }
        public String toString() { return value.substring(start, end); }
    }
}
