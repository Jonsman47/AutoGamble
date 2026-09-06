package com.jonsman.autogamble.payment;
import com.jonsman.autogamble.manager.PlayerSelectionManager;
import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import java.util.*;
import java.util.random.RandomGenerator;

/** One async selection cycle. Never invents player names; only generates search prefixes. */
public final class PrefixPlayerDiscovery {
    public record Request(long token, String prefix) { public String command() { return "/pay " + prefix; } }
    public static final long RETRY = 200_000_000L, TIMEOUT = 3_000_000_000L;
    private final RandomGenerator random;
    private final int prefixLength;
    private final int maximumLength, limit;
    private final Set<String> tried = new HashSet<>();
    private final Set<Character> attempted = new HashSet<>();
    private final Map<String, Candidate> known = new LinkedHashMap<>();
    private List<Candidate> candidates = List.of();
    private long generation, nextAt, requestedAt;
    private boolean active, pending, ready;
    private String prefix = "none", selected = "none";
    private int count;
    public PrefixPlayerDiscovery(RandomGenerator random) { this(random, 1); }
    public PrefixPlayerDiscovery(RandomGenerator random, int prefixLength) {
        if (prefixLength < 1 || prefixLength > 2) throw new IllegalArgumentException("Prefix length must be 1–2");
        this.random = random; this.prefixLength = prefixLength; this.maximumLength = prefixLength; this.limit = 26;
    }
    /** Runtime range constructor; legacy fixed-prefix constructors retain their compatibility policy. */
    public PrefixPlayerDiscovery(RandomGenerator random, int minimum, int maximum) {
        if (minimum < 1 || maximum > 3 || maximum < minimum) throw new IllegalArgumentException("Invalid prefix range");
        this.random = random; this.prefixLength = minimum; this.maximumLength = maximum; this.limit = 10;
    }
    public String range() { return prefixLength + "–" + maximumLength; }
    public int attempts() { return tried.size(); }
    public int lastLength() { return prefix.equals("none") ? 0 : prefix.length(); }
    public int prefixLength() { return prefixLength; }
    public Optional<Request> poll(long now, PlayerSelectionManager history, boolean preferUnpaid) {
        if (!active) { active = true; attempted.clear(); tried.clear(); known.clear(); candidates = List.of(); ready = false; nextAt = now; }
        if (ready) return Optional.empty();
        if (pending) {
            if (now - requestedAt < TIMEOUT) return Optional.empty();
            pending = false; generation++; nextAt = now + RETRY;
        }
        if (now - nextAt < 0) return Optional.empty();
        if ((limit == 10 ? tried.size() : attempted.size()) == limit) {
            // Only reset the paid cycle after exhausting every first letter, never per-prefix.
            candidates = List.copyOf(known.values()); ready = true; count = candidates.size(); tried.clear();
            return Optional.empty();
        }
        if (limit == 10) {
            // Rejection sampling preserves uniform independent length/letter draws; bound pathological injected RNGs.
            String generated = null;
            for (int trial = 0; trial < 256; trial++) {
                int length = prefixLength + random.nextInt(maximumLength - prefixLength + 1);
                var value = new StringBuilder();
                for (int i = 0; i < length; i++) value.append((char) ('a' + random.nextInt(26)));
                if (!tried.contains(value.toString())) { generated = value.toString(); break; }
            }
            if (generated == null) { candidates = List.of(); ready = true; tried.clear(); return Optional.empty(); }
            prefix = generated;
        } else {
        var letters = new ArrayList<Character>();
        for (char ch = 'a'; ch <= 'z'; ch++) if (!attempted.contains(ch)) letters.add(ch);
        char ch = letters.get(random.nextInt(letters.size())); attempted.add(ch);
        prefix = String.valueOf(ch);
        if (prefixLength == 2) prefix += (char) ('a' + random.nextInt(26));
        }
        tried.add(prefix);
        pending = true; requestedAt = now;
        return Optional.of(new Request(++generation, prefix));
    }
    public static boolean validName(String name, String local, boolean skipNumeric) {
        return name != null && name.matches("[A-Za-z0-9_]{2,16}") && !name.equalsIgnoreCase(local)
                && (!skipNumeric || !name.matches("[0-9]+"));
    }
    public boolean complete(long token, Collection<String> names, String local, boolean skipNumeric,
            FailedTargetBlacklist failed, PlayerSelectionManager history, boolean preferUnpaid, long now) {
        if (!active || !pending || token != generation) return false;
        pending = false;
        var filtered = new LinkedHashMap<String, Candidate>();
        for (String name : names) if (validName(name, local, skipNumeric)
                && name.regionMatches(true, 0, prefix, 0, prefix.length()) && !failed.contains(name, now))
            filtered.putIfAbsent(name.toLowerCase(Locale.ROOT), new Candidate(null, name));
        known.putAll(filtered); count = filtered.size();
        candidates = filtered.values().stream().filter(c -> !preferUnpaid || !history.wasPaid(c.username())).toList();
        ready = !candidates.isEmpty(); nextAt = now + RETRY;
        return true;
    }
    public boolean ready() { return ready; }
    public List<Candidate> candidates() { return candidates; }
    public String prefix() { return prefix; }
    public int count() { return count; }
    public String selected() { return selected; }
    public void selected(String name) { selected = name; tried.clear(); }
    public void cancel() { generation++; active = pending = ready = false; candidates = List.of(); known.clear(); attempted.clear(); tried.clear(); }
    public void reset() { cancel(); prefix = selected = "none"; count = 0; }
}
