# Payment architecture — updated for 1.0.1

The architecture introduced in Stage 3 is retained. Current versions and setup are documented in
the root README and PATCH-1.0.1.md: Minecraft 26.2, Java 25, Fabric Loader 0.19.3, config schema 4.

The flow is GAME event → normalized text → parser → structured receipt → deduplication → outgoing
tracking → bet limits → exactly one roll → fixed payout job → FIFO processor → shared dispatcher.
Dry Run remains ON by default and the final command gate covers both advertising and payouts.

The user has now supplied authentic DonutSMP incoming examples in the format
`<player> paid you $ <amount>`. DonutSmpPattern supplies that built-in independently of the custom
incomingPaymentPatterns list. Plain values and case-insensitive K/M/B/T amounts use BigDecimal and
expand before all monetary comparisons. The README includes the exact regex and bounds.

The schema migration preserves custom patterns; donutSmpIncomingEnabled defaults true. An exact custom
copy of the built-in takes precedence, including when disabled. Pattern matching returns one receipt
at most. Invalid syntax, malformed money, unsupported suffixes and player-chat messages are rejected.

Receipt deduplication conservatively uses canonical sender/amount for two seconds by default.
Outgoing tracking records target, canonical amount, time and source before real command dispatch and
rejects matching incoming candidates for ten seconds by default. Both caches are bounded and expire.
Without transaction IDs, closely spaced identical legitimate receipts can be suppressed and late
replays can be accepted. Those limitations have not changed.

Bet limits and queue capacity are checked before accepting a roll. Winners use received amount times
the entire configured multiplier, rounding HALF_UP to two decimal places. Invalid bets are ignored
without automatic refunds. Up to 256 winner jobs wait in FIFO order; each gets a fresh winner delay.
Due winners take priority over advertising and both share a one-command-per-tick gate.

Blocked/offline heads wait and retry after at least 500 ms. An ambiguous command send is not retried,
because doing so could double-pay. Local dispatch does not prove server acceptance. Disabling the
master/gambling switch clears queued winners with a log; session changes clear all transient state.
Mode changes discard simulated jobs before real payments can be enabled.

Outgoing confirmations, errors, real-server acceptance and transaction identity remain unverified.
Use Dry Run for initial live tests. All code interaction with Minecraft remains on the client thread.
