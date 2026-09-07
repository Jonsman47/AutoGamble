package com.jonsman.autogamble.history;

import com.google.gson.*;
import com.jonsman.autogamble.config.AutoGambleConfig;
import com.jonsman.autogamble.config.MoneyValues;
import com.jonsman.autogamble.payment.OutgoingPaymentTracker;
import org.slf4j.Logger;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Central analytics consumer. Session state is volatile; customer and ROI aggregates are durable. */
public final class AnalyticsEngine implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().registerTypeAdapter(BigDecimal.class, new SignedMoneyAdapter()).setPrettyPrinting().create();
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    public record Customer(String player, BigDecimal paidBy, BigDecimal paidBack, BigDecimal net,
                           long bets, long wins, long losses, long lastPayment) {}
    public record Snapshot(long sessionStarted, long now, long paymentsReceived, BigDecimal moneyReceived,
                           BigDecimal moneyPaid, BigDecimal gamblingProfit, BigDecimal advertisingCost,
                           long bets, long wins, long losses, BigDecimal gamblingStake, int uniqueCustomers, int returningCustomers,
                           long sessionAdvertisingPayments, BigDecimal sessionAdvertisingSpend,
                           int sessionUniqueAdvertised, int sessionConverted, BigDecimal sessionAttributedRevenue,
                           BigDecimal sessionAttributedProfit, long lifetimeAdvertisingPayments,
                           BigDecimal lifetimeAdvertisingSpend, int lifetimeUniqueAdvertised,
                           int lifetimeConverted, BigDecimal lifetimeAttributedRevenue,
                           BigDecimal lifetimeAttributedProfit, List<Customer> customers, String error) {
        public BigDecimal trackedNetProfit() { return moneyReceived.subtract(moneyPaid); }
        public double elapsedSeconds() { return Math.max(0, now - sessionStarted) / 1000.0; }
        public double paymentsPerMinute() { return elapsedSeconds() <= 0 ? 0 : paymentsReceived * 60.0 / elapsedSeconds(); }
        public double betsPerMinute() { return elapsedSeconds() <= 0 ? 0 : bets * 60.0 / elapsedSeconds(); }
    }
    private static final class CustomerData {
        String player; BigDecimal paidBy = ZERO, paidBack = ZERO; long bets, wins, losses, lastPayment;
    }
    private static final class Touch { long time; BigDecimal cost = ZERO; }
    private static final class Data {
        int version = 1;
        Map<String, CustomerData> customers = new HashMap<>();
        long advertisingPayments; BigDecimal advertisingSpend = ZERO;
        Set<String> uniqueAdvertised = new HashSet<>(), converted = new HashSet<>();
        BigDecimal attributedRevenue = ZERO, attributedProfit = ZERO;
        Map<String, Touch> touches = new HashMap<>();
        Map<String, Long> convertedUntil = new HashMap<>();
    }
    private static final class SignedMoneyAdapter implements JsonSerializer<BigDecimal>, JsonDeserializer<BigDecimal> {
        @Override public JsonElement serialize(BigDecimal value, java.lang.reflect.Type type, JsonSerializationContext context) {
            return new JsonPrimitive(value.stripTrailingZeros().toPlainString());
        }
        @Override public BigDecimal deserialize(JsonElement json, java.lang.reflect.Type type, JsonDeserializationContext context) {
            try {
                if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
                String value = json.getAsString();
                if (!value.matches("-?[0-9]+(?:\\.[0-9]{1,2})?") || value.length() > 96) throw new IllegalArgumentException();
                return new BigDecimal(value);
            } catch (RuntimeException ex) { throw new JsonParseException("Invalid signed money", ex); }
        }
    }
    private final Path path;
    private final Logger log;
    private final ScheduledExecutorService writer;
    private Data data = new Data();
    private String error = "";
    private boolean dirty, scheduled;
    private long started;
    private long paymentsReceived, bets, sessionAdvertisingPayments;
    private long wins, losses;
    private BigDecimal received = ZERO, paid = ZERO, gamblingProfit = ZERO, gamblingStake = ZERO, advertisingCost = ZERO;
    private final Set<String> sessionUnique = new HashSet<>(), sessionReturning = new HashSet<>();
    private final Set<String> sessionAdvertised = new HashSet<>(), sessionConverted = new HashSet<>();
    private final Map<String, Touch> sessionTouches = new HashMap<>();
    private final Map<String, Long> sessionConvertedUntil = new HashMap<>();
    private BigDecimal sessionAttributedRevenue = ZERO, sessionAttributedProfit = ZERO;
    private record Attribution(boolean session, boolean lifetime) {}
    private final Map<String, ArrayDeque<Attribution>> payoutAttribution = new HashMap<>();

    public AnalyticsEngine(Path path, Logger log) {
        this.path = path; this.log = log;
        writer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "AutoGamble-Analytics"); t.setDaemon(true); return t; });
        load(); resetSession(System.currentTimeMillis());
    }

    public static BigDecimal expectedProfit(BigDecimal bet, double winChance, double payoutMultiplier) {
        if (bet == null || bet.signum() < 0 || !Double.isFinite(winChance) || !Double.isFinite(payoutMultiplier)) throw new IllegalArgumentException();
        return bet.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(winChance).multiply(BigDecimal.valueOf(payoutMultiplier))));
    }
    public static BigDecimal roi(BigDecimal profit, BigDecimal spend) {
        return spend == null || spend.signum() == 0 ? ZERO : profit.multiply(BigDecimal.valueOf(100)).divide(spend, 4, RoundingMode.HALF_UP);
    }

    public synchronized void incoming(String player, BigDecimal amount, long now, AutoGambleConfig c) {
        String key = key(player); if (key == null || amount == null || amount.signum() <= 0) return;
        paymentsReceived++; received = received.add(amount); sessionUnique.add(key);
        CustomerData customer = data.customers.computeIfAbsent(key, ignored -> new CustomerData());
        customer.player = player; customer.paidBy = customer.paidBy.add(amount); customer.lastPayment = now;
        Attribution attribution = attribution(key, now, c, true);
        if (attribution.session) { sessionAttributedRevenue = sessionAttributedRevenue.add(amount); sessionAttributedProfit = sessionAttributedProfit.add(amount); }
        if (attribution.lifetime) { data.attributedRevenue = data.attributedRevenue.add(amount); data.attributedProfit = data.attributedProfit.add(amount); }
        changed();
    }

    public synchronized void acceptedGamble(String player, BigDecimal amount, boolean firstEver, boolean won,
                                            BigDecimal payout, long now, AutoGambleConfig c) {
        String key = key(player); if (key == null) return;
        bets++; if (won) wins++; else losses++;
        gamblingStake = gamblingStake.add(amount); gamblingProfit = gamblingProfit.add(amount); if (!firstEver) sessionReturning.add(key);
        CustomerData customer = data.customers.computeIfAbsent(key, ignored -> new CustomerData());
        customer.player = player; customer.bets++; if (won) customer.wins++; else customer.losses++;
        Attribution attribution = attribution(key, now, c, false);
        if (won && payout != null && !c.dryRunMode) payoutAttribution.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(attribution);
        changed();
    }

    public synchronized void outgoing(String player, BigDecimal amount, OutgoingPaymentTracker.Source source, long now) {
        String key = key(player); if (key == null || amount == null || amount.signum() <= 0) return;
        paid = paid.add(amount);
        if (source == OutgoingPaymentTracker.Source.GAMBLE_PAYOUT) {
            gamblingProfit = gamblingProfit.subtract(amount);
            CustomerData customer = data.customers.computeIfAbsent(key, ignored -> new CustomerData());
            customer.player = player; customer.paidBack = customer.paidBack.add(amount);
            ArrayDeque<Attribution> queue = payoutAttribution.get(key);
            if (queue != null && !queue.isEmpty()) {
                Attribution attribution = queue.removeFirst();
                if (attribution.session) sessionAttributedProfit = sessionAttributedProfit.subtract(amount);
                if (attribution.lifetime) data.attributedProfit = data.attributedProfit.subtract(amount);
            }
        } else if (source == OutgoingPaymentTracker.Source.ADVERTISING) {
            advertisingCost = advertisingCost.add(amount); sessionAdvertisingPayments++; sessionAdvertised.add(key);
            data.advertisingPayments++; data.advertisingSpend = data.advertisingSpend.add(amount); data.uniqueAdvertised.add(key);
            sessionAttributedProfit = sessionAttributedProfit.subtract(amount); data.attributedProfit = data.attributedProfit.subtract(amount);
            Touch touch = new Touch(); touch.time = now; touch.cost = amount; data.touches.put(key, touch);
            Touch sessionTouch = new Touch(); sessionTouch.time = now; sessionTouch.cost = amount; sessionTouches.put(key, sessionTouch);
            data.convertedUntil.remove(key);
            sessionConvertedUntil.remove(key);
        }
        changed();
    }

    private Attribution attribution(String key, long now, AutoGambleConfig c, boolean mayConvert) {
        boolean session = active(sessionConvertedUntil.get(key), now), lifetime = active(data.convertedUntil.get(key), now);
        if (mayConvert) {
            long conversionMs = c.autoPayConversionWindowSeconds * 1000L;
            long end = now + c.autoPayAttributionDurationSeconds * 1000L;
            Touch sessionTouch = sessionTouches.get(key);
            if (!session && sessionTouch != null && now - sessionTouch.time <= conversionMs) {
                session = true; sessionTouches.remove(key); sessionConvertedUntil.put(key, end); sessionConverted.add(key);
            }
            Touch lifetimeTouch = data.touches.get(key);
            if (!lifetime && lifetimeTouch != null && now - lifetimeTouch.time <= conversionMs) {
                lifetime = true; data.touches.remove(key); data.convertedUntil.put(key, end); data.converted.add(key);
            }
        }
        return new Attribution(session, lifetime);
    }
    private static boolean active(Long until, long now) { return until != null && now <= until; }

    public synchronized void resetSession(long now) {
        started = now; paymentsReceived = bets = wins = losses = sessionAdvertisingPayments = 0;
        received = paid = gamblingProfit = gamblingStake = advertisingCost = ZERO;
        sessionUnique.clear(); sessionReturning.clear(); sessionAdvertised.clear(); sessionConverted.clear(); sessionTouches.clear(); sessionConvertedUntil.clear();
        sessionAttributedRevenue = sessionAttributedProfit = ZERO; payoutAttribution.clear();
    }

    public synchronized Snapshot snapshot(long now) {
        List<Customer> customers = data.customers.values().stream().map(c -> new Customer(c.player, c.paidBy, c.paidBack,
                c.paidBy.subtract(c.paidBack), c.bets, c.wins, c.losses, c.lastPayment))
                .sorted(Comparator.comparing(Customer::net).reversed().thenComparing(Customer::player, String.CASE_INSENSITIVE_ORDER)).toList();
        return new Snapshot(started, now, paymentsReceived, received, paid, gamblingProfit, advertisingCost, bets,
                wins, losses, gamblingStake, sessionUnique.size(), sessionReturning.size(), sessionAdvertisingPayments, advertisingCost,
                sessionAdvertised.size(), sessionConverted.size(), sessionAttributedRevenue, sessionAttributedProfit,
                data.advertisingPayments, data.advertisingSpend, data.uniqueAdvertised.size(), data.converted.size(),
                data.attributedRevenue, data.attributedProfit, customers, error);
    }

    private void load() {
        try {
            if (!Files.exists(path)) return;
            var root = JsonParser.parseString(Files.readString(path));
            if (!root.isJsonObject()) throw new IOException("Expected JSON object");
            Data loaded = GSON.fromJson(root, Data.class);
            if (loaded == null || loaded.version != 1) throw new IOException("Unsupported analytics data version");
            repair(loaded); data = loaded;
        } catch (Exception ex) {
            error = "Analytics data was corrupt and has been reset"; log.warn("[AutoGamble] Cannot load analytics data", ex);
            try { AtomicFiles.backup(path); } catch (IOException backup) { log.warn("[AutoGamble] Cannot back up corrupt analytics data", backup); }
            data = new Data();
        }
    }
    private static void repair(Data d) throws IOException {
        if (d.customers == null || d.uniqueAdvertised == null || d.converted == null || d.touches == null || d.convertedUntil == null
                || d.advertisingSpend == null || d.attributedRevenue == null || d.attributedProfit == null) throw new IOException("Missing analytics fields");
        for (CustomerData c : d.customers.values()) if (c == null || c.paidBy == null || c.paidBack == null) throw new IOException("Bad customer data");
    }
    private void changed() {
        dirty = true;
        if (!scheduled) { scheduled = true; writer.schedule(this::writePending, 250, TimeUnit.MILLISECONDS); }
    }
    private void writePending() {
        String json;
        synchronized (this) { scheduled = false; if (!dirty) return; dirty = false; json = GSON.toJson(data) + System.lineSeparator(); }
        try { AtomicFiles.write(path, json); }
        catch (IOException | SecurityException ex) { synchronized (this) { error = "Could not save analytics data"; dirty = true; } log.warn("[AutoGamble] Cannot save analytics data", ex); }
    }
    public void flush() { writePending(); }
    @Override public void close() { flush(); writer.shutdown(); try { writer.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } }
    private static String key(String player) { return player != null && player.matches("[A-Za-z0-9_]{2,16}") ? player.toLowerCase(Locale.ROOT) : null; }
}
