package com.jonsman.autogamble.manager;

import com.google.gson.*;
import com.jonsman.autogamble.config.*;
import com.jonsman.autogamble.history.AtomicFiles;
import com.jonsman.autogamble.payment.PaymentSender;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import org.slf4j.LoggerFactory;

/** Edge-trigger state stays on the client thread; a single worker persists immutable snapshots. */
public final class BalanceRuleEngine implements AutoCloseable {
    private static final class State {
        boolean armed = true;
        long lastFired = Long.MIN_VALUE;
        String signature;
    }
    private final Map<String, State> states = new HashMap<>();
    private final Map<String, Long> simulations = new HashMap<>();
    private final ExecutorService disk = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "AutoGamble balance state"); t.setDaemon(true); return t; });
    private final Path file;
    private final CompletableFuture<Map<String, State>> initial;
    private boolean loaded, writable = true;
    private static final Gson JSON = new GsonBuilder().create();
    public BalanceRuleEngine() { this(null); }
    public BalanceRuleEngine(Path file) {
        this.file = file; initial = CompletableFuture.supplyAsync(this::load, disk);
    }
    private Map<String, State> load() {
        Map<String, State> result = new HashMap<>();
        if (file == null || !Files.exists(file)) return result;
        try {
            if (Files.size(file) > 1_000_000) throw new IllegalArgumentException("Oversized rule state");
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (o.get("version").getAsInt() != 1) throw new IllegalArgumentException("Unknown version");
            for (var e : o.getAsJsonObject("states").entrySet()) {
                State s = JSON.fromJson(e.getValue(), State.class);
                if (s == null || s.signature == null) throw new IllegalArgumentException("Invalid state");
                result.put(e.getKey(), s);
            }
        } catch (Exception ex) {
            LoggerFactory.getLogger("autogamble").warn("[AutoGamble] Cannot read balance rule state", ex);
            try { AtomicFiles.backup(file); } catch (Exception backup) { writable = false; LoggerFactory.getLogger("autogamble").warn("[AutoGamble] Cannot back up rule state; live rules blocked", backup); }
            result.clear();
        }
        return result;
    }
    public void awaitLoaded() { initial.join(); }
    public void tick(AutoGambleConfig c, KnownBalance balance, long nowNanos, long nowMillis,
                     BiFunction<String, BigDecimal, PaymentSender.Result> sender) {
        if (!initial.isDone()) return;
        if (!loaded) { states.putAll(initial.join()); loaded = true; }
        if (!writable || !c.enabled || !c.automaticBalancePaymentsEnabled) return;
        BigDecimal current = balance.current(nowNanos); if (current == null) return;
        boolean changed = false;
        Set<String> activeIds = new HashSet<>();
        c.balancePaymentRules.forEach(r -> activeIds.add(r.id));
        changed |= states.keySet().removeIf(id -> !activeIds.contains(id));
        simulations.keySet().removeIf(id -> !activeIds.contains(id));
        // Observe all edges before one possible dispatch invalidates this balance observation.
        for (BalanceRule r : c.balancePaymentRules) {
            if (!r.valid()) continue;
            State s = states.computeIfAbsent(r.id, ignored -> new State());
            if (s.signature == null) s.signature = r.signature();
            if (!s.signature.equals(r.signature())) { s.signature = r.signature(); s.armed = false; changed = true; }
            if (current.compareTo(r.threshold) < 0 && !s.armed) { s.armed = true; changed = true; }
        }
        for (BalanceRule r : c.balancePaymentRules) {
            if (!r.enabled || !r.valid()) continue;
            State s = states.get(r.id);
            if (!s.armed || current.compareTo(r.threshold) < 0 || s.lastFired != Long.MIN_VALUE && nowMillis - s.lastFired < r.cooldownSeconds * 1000L) continue;
            if (current.compareTo(r.amount) < 0) {
                logOnce(r.id, nowNanos, "[AutoGamble] Balance rule skipped: insufficient known balance for " + r.player); continue;
            }
            if (c.dryRunMode) {
                logOnce(r.id, nowNanos, "[AutoGamble] DRY RUN: Balance rule would pay " + r.player + " " + MoneyValues.display(r.amount) + " at balance " + MoneyValues.display(current));
                continue;
            }
            PaymentSender.Result result = sender.apply(r.player, r.amount);
            if (result != PaymentSender.Result.RETRY_LATER) {
                // An ambiguous send must not be retried while above threshold either.
                s.armed = false; s.lastFired = nowMillis; changed = true; balance.invalidate(); break;
            }
        }
        if (changed) persist();
    }
    private void logOnce(String id, long now, String message) {
        Long last = simulations.get(id);
        if (last == null || now - last >= 30_000_000_000L) { LoggerFactory.getLogger("autogamble").info(message); simulations.put(id, now); }
    }
    private void persist() {
        if (file == null || !writable) return;
        JsonObject o = new JsonObject(); o.addProperty("version", 1); o.add("states", JSON.toJsonTree(states)); String text = JSON.toJson(o);
        disk.execute(() -> { try { AtomicFiles.write(file, text); } catch (Exception ex) { LoggerFactory.getLogger("autogamble").warn("[AutoGamble] Cannot save balance rule state", ex); } });
    }
    @Override public void close() { disk.shutdown(); try { disk.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } }
}
