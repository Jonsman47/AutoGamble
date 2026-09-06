package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import java.util.*;

/** Client-thread cache. Request tokens prevent late responses crossing sessions or refreshes. */
public final class CommandSuggestionPlayerSource {
    public static final long REFRESH_NANOS = 20_000_000_000L;
    private long generation, nextRefresh;
    private boolean requested;
    private List<Candidate> candidates = List.of();
    public long beginRequest(long now) {
        if (requested && now - nextRefresh < 0) return -1;
        requested = true;
        nextRefresh = now + REFRESH_NANOS;
        candidates = List.of();
        return ++generation;
    }
    public boolean complete(long token, Collection<String> names, String local) {
        if (token != generation) return false;
        var unique = new LinkedHashMap<String, Candidate>();
        for (String name : names) {
            if (name != null && name.matches("[A-Za-z0-9_]{3,16}") && !name.equalsIgnoreCase(local))
                unique.putIfAbsent(name.toLowerCase(Locale.ROOT), new Candidate(null, name));
        }
        candidates = List.copyOf(unique.values());
        return true;
    }
    public List<Candidate> candidates(long now) {
        return requested && now - nextRefresh < 0 ? candidates : List.of();
    }
    public List<Candidate> choose(long now, List<Candidate> tab) {
        var known = candidates(now);
        return known.isEmpty() ? tab : known;
    }
    public String source(long now) { return candidates(now).isEmpty() ? "TAB_FALLBACK" : "PAY_COMMAND_SUGGESTIONS"; }
    public void reset() { generation++; requested = false; candidates = List.of(); }
}
