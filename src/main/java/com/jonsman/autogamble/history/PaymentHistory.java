package com.jonsman.autogamble.history;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.jonsman.autogamble.config.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

/** One worker owns all durable data. The client only submits immutable events and reads snapshots. */
public final class PaymentHistory implements AutoCloseable {
    public enum Direction { RECEIVED, PAID }
    public record Transaction(Direction direction, String player, BigDecimal amount, long timestamp, String source) {}
    public record Customer(String name, BigDecimal total) {}
    public record Snapshot(boolean ready, int stored, int receivedPlayers, int paidPlayers,
                           Set<String> followed, List<Customer> eligible, String error,
                           List<Customer> receivedTotals, List<Customer> paidTotals,
                           List<Transaction> transactions, List<String> followedPlayers) {}
    private record Policy(boolean paid, boolean top, boolean recent, boolean received, boolean sent,
                          BigDecimal min, BigDecimal max, int lines, int retention, boolean newest, BigDecimal followThreshold) {
        static Policy of(AutoGambleConfig c) { return new Policy(c.generatePaymentsToPlayersReport, c.generateTopCustomersReport,
            c.generateRecentPaymentsReport, c.recentShowReceived, c.recentShowPaid, c.recentMinimumAmount, c.recentMaximumAmount,
            c.recentMaxLines, c.storedTransactionHistoryLimit, c.recentNewestFirst, c.autoFollowThreshold); }
    }
    private static final Gson JSON = MoneyValues.gson().setPrettyPrinting().create();
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "AutoGamble history"); t.setDaemon(true); return t;
    });
    private final Path root;
    private final Map<String, BigDecimal> received = new HashMap<>(), paid = new HashMap<>();
    private final Map<String, String> names = new HashMap<>(), followed = new LinkedHashMap<>();
    private final Deque<Transaction> history = new ArrayDeque<>();
    private final Set<Path> protectedFiles = new HashSet<>();
    private Policy policy;
    private boolean dirty, followedDirty;
    private String error = "";
    private volatile Snapshot snapshot = new Snapshot(false, 0, 0, 0, Set.of(), List.of(), "",
            List.of(), List.of(), List.of(), List.of());
    public PaymentHistory(Path root, AutoGambleConfig config) {
        this.root = root; policy = Policy.of(config);
        worker.setRemoveOnCancelPolicy(true);
        worker.execute(() -> { load(); dirty = true; flush(); });
        worker.scheduleWithFixedDelay(this::flush, 500, 500, TimeUnit.MILLISECONDS);
    }
    public Path root() { return root; }
    public Path reports() { return root.resolve("reports"); }
    public Snapshot snapshot() { return snapshot; }
    public void configure(AutoGambleConfig config) {
        Policy next = Policy.of(config);
        worker.execute(() -> { policy = next; trim(); dirty = true; });
    }
    public void record(Direction direction, String player, BigDecimal amount, String source) {
        record(new Transaction(direction, player, amount, System.currentTimeMillis(), source));
    }
    public void record(Transaction t) {
        if (t == null || t.direction == null || !validName(t.player) || !MoneyValues.valid(t.amount, true)) return;
        worker.execute(() -> {
            String key = key(t.player); names.put(key, t.player);
            (t.direction == Direction.RECEIVED ? received : paid).merge(key, t.amount, BigDecimal::add);
            history.addLast(t); trim(); dirty = true;
        });
    }
    public void followed(String name) {
        if (!validName(name)) return;
        worker.execute(() -> { followed.putIfAbsent(key(name), name); followedDirty = true; writeFollowed(); publish(); });
    }
    public void clearFollowed() {
        worker.execute(() -> { followed.clear(); followedDirty = true; writeFollowed(); publish(); });
    }
    public void refresh() { worker.execute(() -> { dirty = true; flush(); }); }
    /** Test/shutdown barrier; never used during gameplay. */
    public void awaitWrites() throws Exception { worker.submit(() -> { dirty = true; flush(); }).get(30, TimeUnit.SECONDS); }
    private void trim() { while (history.size() > policy.retention) history.removeFirst(); }
    public static boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9_]{2,16}"); }
    public static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private void load() {
        Path totals = root.resolve("data/payment_totals.json");
        try {
            if (Files.exists(totals)) {
                if (Files.size(totals) > 512L * 1024 * 1024) throw new IllegalArgumentException("History file too large");
                try (JsonReader reader = new JsonReader(Files.newBufferedReader(totals))) {
                    reader.setStrictness(Strictness.STRICT);
                    Set<String> fields = new HashSet<>(); reader.beginObject();
                    while (reader.hasNext()) {
                        String field = reader.nextName();
                        if (!fields.add(field)) throw new IllegalArgumentException("Duplicate data field");
                        switch (field) {
                            case "version" -> { if (reader.nextInt() != 1) throw new IllegalArgumentException("Unsupported history version"); }
                            case "receivedTotals" -> readTotals(reader, received);
                            case "paidTotals" -> readTotals(reader, paid);
                            case "displayNames" -> {
                                reader.beginObject();
                                while (reader.hasNext()) { String key = reader.nextName(), display = reader.nextString();
                                    if (validName(display) && key(display).equals(key)) names.put(key, display);
                                }
                                reader.endObject();
                            }
                            case "transactions" -> {
                                reader.beginArray();
                                while (reader.hasNext()) {
                                    Transaction t = JSON.fromJson(reader, Transaction.class);
                                    if (t == null || t.direction == null || !validName(t.player) || !MoneyValues.valid(t.amount, true) || t.timestamp < 0)
                                        throw new IllegalArgumentException("Invalid transaction");
                                    history.addLast(t); trim();
                                }
                                reader.endArray();
                            }
                            default -> reader.skipValue();
                        }
                    }
                    reader.endObject();
                    if (!fields.containsAll(Set.of("version", "receivedTotals", "paidTotals", "displayNames", "transactions"))
                            || reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Incomplete history data");
                }
            }
        } catch (Exception ex) { corrupt(totals, ex); received.clear(); paid.clear(); names.clear(); history.clear(); }
        Path list = root.resolve("followed_players.txt");
        try {
            if (Files.exists(list)) {
                if (Files.size(list) > 16L * 1024 * 1024) throw new IllegalArgumentException("Followed-player file too large");
                try (var lines = Files.lines(list)) { lines.forEach(line -> {
                    String name = line.trim(); if (validName(name)) followed.putIfAbsent(key(name), name);
                }); }
            }
            followedDirty = true; writeFollowed();
        } catch (Exception ex) { corrupt(list, ex); followed.clear(); }
    }
    private void readTotals(JsonReader reader, Map<String, BigDecimal> target) throws java.io.IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!validName(name)) throw new IllegalArgumentException("Invalid player");
            BigDecimal n = MoneyValues.parse(reader.nextString());
            if (n.signum() > 0) target.merge(key(name), n, BigDecimal::add);
        }
        reader.endObject();
    }
    private void corrupt(Path path, Exception ex) {
        warn("Cannot read " + path + "; using empty data", ex);
        try { AtomicFiles.backup(path); } catch (Exception backup) {
            protectedFiles.add(path); warn("Backup failed; will not overwrite " + path, backup);
        }
    }
    private void warn(String message, Exception ex) {
        error = message; LoggerFactory.getLogger("autogamble").warn("[AutoGamble] " + message, ex);
    }
    private void write(Path path, String content) throws Exception {
        if (protectedFiles.contains(path)) throw new IllegalStateException("Protected corrupt file: " + path);
        AtomicFiles.write(path, content);
    }
    private void writeFollowed() {
        try { write(root.resolve("followed_players.txt"), String.join("\n", followed.values()) + (followed.isEmpty() ? "" : "\n")); followedDirty = false; }
        catch (Exception ex) { warn("Cannot persist followed players", ex); }
    }
    private void publish() {
        List<Customer> eligible = received.entrySet().stream().filter(e -> e.getValue().compareTo(policy.followThreshold) >= 0 && !followed.containsKey(e.getKey()))
            .sorted(Map.Entry.comparingByKey()).map(e -> new Customer(names.getOrDefault(e.getKey(), e.getKey()), e.getValue())).toList();
        List<String> followedPlayers = followed.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        snapshot = new Snapshot(protectedFiles.isEmpty() && !followedDirty, history.size(), received.size(), paid.size(),
                Set.copyOf(followed.keySet()), eligible, error, customerTotals(received), customerTotals(paid),
                List.copyOf(history), followedPlayers);
    }
    private List<Customer> customerTotals(Map<String, BigDecimal> totals) {
        return totals.entrySet().stream().filter(e -> e.getValue().signum() > 0)
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new Customer(names.getOrDefault(e.getKey(), e.getKey()), e.getValue())).toList();
    }
    private void flush() {
        if (followedDirty) { writeFollowed(); publish(); }
        if (!dirty) return;
        try {
            // Totals and retained transactions share one atomic commit, avoiding mismatched generations.
            Path file = root.resolve("data/payment_totals.json");
            if (protectedFiles.contains(file)) throw new IllegalStateException("Protected corrupt file: " + file);
            AtomicFiles.write(file, output -> {
                JsonWriter writer = new JsonWriter(output); writer.setIndent("  "); writer.beginObject(); writer.name("version").value(1);
                writer.name("receivedTotals"); writeTotals(writer, received); writer.name("paidTotals"); writeTotals(writer, paid);
                writer.name("displayNames").beginObject(); for (var entry : names.entrySet()) writer.name(entry.getKey()).value(entry.getValue()); writer.endObject();
                writer.name("transactions").beginArray(); for (var transaction : history) JSON.toJson(transaction, Transaction.class, writer);
                writer.endArray(); writer.endObject(); writer.flush(); output.write('\n');
            });
            if (policy.paid) write(reports().resolve("payments_to_players.txt"), totalsText(paid));
            if (policy.top) write(reports().resolve("top_customers.txt"), totalsText(received));
            if (policy.recent) write(reports().resolve("recent_payments.txt"), recentText());
            dirty = false;
        } catch (Exception ex) { warn("Cannot update payment data/reports; will retry", ex); }
        publish();
    }
    private void writeTotals(JsonWriter writer, Map<String, BigDecimal> totals) throws java.io.IOException {
        writer.beginObject(); for (var entry : totals.entrySet()) writer.name(entry.getKey()).value(MoneyValues.plain(entry.getValue())); writer.endObject();
    }
    private String totalsText(Map<String, BigDecimal> totals) {
        StringBuilder text = new StringBuilder();
        totals.entrySet().stream().filter(e -> e.getValue().signum() > 0)
            .sorted(Map.Entry.<String,BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .forEach(e -> text.append(names.getOrDefault(e.getKey(), e.getKey())).append(" | ").append(MoneyValues.plain(e.getValue())).append('\n'));
        return text.toString();
    }
    private String recentText() {
        var format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        Comparator<Transaction> order = Comparator.comparingLong(Transaction::timestamp);
        if (policy.newest) order = order.reversed();
        // Reverse insertion order first so equal timestamps retain newest-event-first ordering.
        List<Transaction> events = new ArrayList<>(history); if (policy.newest) Collections.reverse(events);
        StringBuilder text = new StringBuilder();
        events.stream().filter(t -> t.direction == Direction.RECEIVED ? policy.received : policy.sent)
            .filter(t -> t.amount.compareTo(policy.min) >= 0 && (policy.max == null || t.amount.compareTo(policy.max) <= 0))
            .sorted(order).limit(policy.lines).forEach(t -> text.append(format.format(Instant.ofEpochMilli(t.timestamp)))
                .append(" | ").append(t.direction).append(" | ").append(t.player).append(" | ").append(MoneyValues.plain(t.amount)).append('\n'));
        return text.toString();
    }
    @Override public void close() {
        if (worker.isShutdown()) return;
        try { awaitWrites(); } catch (Exception ex) { warn("Could not flush history during shutdown", ex); }
        worker.shutdown();
    }
}
