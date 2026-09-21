package com.jonsman.autogamble.targeting;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;

/** Exact autocomplete matching shared by runtime verification and deterministic tests. */
public final class OnlineVerification {
    private OnlineVerification() {}
    /** Completed arguments may produce no suggestions; query progressively shorter, still-specific prefixes. */
    public static List<String> prefixes(String username) {
        if (username == null || !username.matches("[A-Za-z0-9_]{2,16}")) return List.of();
        List<String> result = new ArrayList<>();
        for (int removed = 1; removed <= 3 && username.length() - removed >= 1; removed++)
            result.add(username.substring(0,username.length()-removed));
        return List.copyOf(result);
    }
    public static boolean exactUsername(Collection<String> suggestions, String candidate, String localPlayer) {
        return suggestions != null && candidate != null && candidate.matches("[A-Za-z0-9_]{2,16}")
                && (localPlayer == null || !candidate.equalsIgnoreCase(localPlayer))
                && suggestions.stream().anyMatch(value -> value != null && value.equalsIgnoreCase(candidate));
    }
}
