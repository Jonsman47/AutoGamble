package com.jonsman.autogamble.targeting;

import com.jonsman.autogamble.config.AutoGambleConfig;
import java.util.*;
import java.util.random.RandomGenerator;

/** Pure weighted selection; unavailable methods are removed and remaining weights are redistributed. */
public final class WeightedTargetSelector {
    private WeightedTargetSelector() {}
    public static Optional<TargetMethod> select(AutoGambleConfig c, Set<TargetMethod> available, RandomGenerator random) {
        Objects.requireNonNull(c); Objects.requireNonNull(available); Objects.requireNonNull(random);
        int total = 0;
        for (TargetMethod method : TargetMethod.values()) if (available.contains(method)) total += weight(c, method);
        if (total <= 0) return Optional.empty();
        int pick = random.nextInt(total);
        for (TargetMethod method : TargetMethod.values()) if (available.contains(method)) {
            pick -= weight(c, method);
            if (pick < 0) return Optional.of(method);
        }
        return Optional.empty();
    }
    public static int weight(AutoGambleConfig c, TargetMethod method) {
        return switch (method) {
            case SMART_RANDOM -> c.smartRandomWeight;
            case MONEY_LEADERBOARD -> c.moneyLeaderboardWeight;
            case ECONOMY_ACTIVE -> c.economyActiveWeight;
            case EXPERIMENTAL -> c.experimentalWeight;
        };
    }
}
