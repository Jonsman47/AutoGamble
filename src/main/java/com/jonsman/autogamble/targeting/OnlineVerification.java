package com.jonsman.autogamble.targeting;

import java.util.Collection;

/** Exact autocomplete matching shared by runtime verification and deterministic tests. */
public final class OnlineVerification {
    private OnlineVerification() {}
    public static boolean exactUsername(Collection<String> suggestions, String candidate, String localPlayer) {
        return suggestions != null && candidate != null && candidate.matches("[A-Za-z0-9_]{2,16}")
                && (localPlayer == null || !candidate.equalsIgnoreCase(localPlayer))
                && suggestions.stream().anyMatch(value -> value != null && value.equalsIgnoreCase(candidate));
    }
}
