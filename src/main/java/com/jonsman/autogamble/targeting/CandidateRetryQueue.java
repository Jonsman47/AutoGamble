package com.jonsman.autogamble.targeting;

import java.util.List;
import java.util.Optional;

/** A failed online check consumes one candidate, not the entire advertising cycle. */
public final class CandidateRetryQueue {
    private final List<String> candidates;
    private int index;
    public CandidateRetryQueue(List<String> candidates) { this.candidates = List.copyOf(candidates); }
    public Optional<String> next() { return index < candidates.size() ? Optional.of(candidates.get(index++)) : Optional.empty(); }
    public int remaining() { return candidates.size() - index; }
}
