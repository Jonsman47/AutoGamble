package com.jonsman.autogamble.targeting;

public record TargetingDiagnostics(String method, int scanned, int balanceEligible, int localExcluded,
        int recentExcluded, int invalidExcluded, int blockedExcluded, int candidatesAvailable, int candidateChecks, int suggestionRequests,
        int onlineMatches, int verificationFailures, int paymentsAttempted, int paymentsSent,
        String currentCandidate, String lastSuggestionPrefix, String lastRejectedUsername, String lastRejectionReason,
        String lastSuccessfulTarget, long millisSinceLastPayment, String lastResult) {}
