# Stage 3: incoming bets and queued payouts

Historical Stage 3 design notes. For the final 1.0.0 release, schema 3, dry-run defaults and settings UI,
use the root README and FINAL-REPORT.md; those supersede version/default statements below.

This extends the existing Stage 2 code. Minecraft 26.2, Java 25, Gradle 9.5.1, Loom 1.17.20,
Fabric Loader 0.19.5 and Fabric API 0.159.0+26.2 are unchanged. Mod version: 0.3.0.

## Configuration and activation

`config/autogamble.json` now uses `configVersion: 2`. Version 0/unversioned and Stage 2/version 1
files migrate automatically, preserving all existing settings. New options:

| Option | Default | Validation |
| --- | --- | --- |
| incomingPaymentPatterns | `[]` | Up to 16 objects with boolean `enabled` and string `regex`, at most 512 characters each |
| receiptDeduplicationWindowMs | 2000 | 100–60000 ms |
| outgoingPaymentTrackingWindowMs | 10000 | 1000–120000 ms |

All existing settings are retained. Both `enabled` and `gambleEnabled` must be true before a bet
can roll. `gambleEnabled` remains false by default. There are **no enabled patterns by default**.
Until a verified pattern is configured, the installed mod cannot recognize incoming bets.

Edit configuration with Minecraft closed and restart to load. Future GUI updates should call
`ConfigManager.update` on the client thread; revisions refresh the active parser/config automatically.
F8 still toggles the master switch; F9 is still the settings placeholder. `/autogamble status` now adds
gambling enabled state, probability, multiplier, bet limits, pending payouts, receipt-cache count and
the count of valid enabled patterns. Parser syntax problems are logged, never dumped to local chat.

## Pattern contract and message source

The exact hook is `net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME`.
It observes received game/system messages and action-bar messages (`overlay=true`) without cancelling,
replacing or modifying rendering. It does not subscribe to the signed player-chat `CHAT` hook.
`Component.getString()` extracts text at this boundary; the parser and gambling logic use plain Java data.
`ReceivedMessage` also carries SYSTEM, OVERLAY or PLAYER_CHAT provenance; PLAYER_CHAT is rejected.

Every configured regex must match the **entire normalized message** and supply named groups
`(?<sender>...)` and `(?<amount>...)`. Capture the username alone and the monetary number without `$`.
Legacy formatting codes are removed, whitespace is collapsed and nonbreaking spaces become ordinary spaces.
No substring searches, generic payment-word detection or guessed server format is built in.
Patterns with invalid syntax or missing groups are inactive. Message length and regex work are bounded;
pathological expressions fail closed instead of indefinitely blocking the client thread.

`incoming-patterns.example.json` contains **disabled, synthetic examples**, not verified DonutSMP formats.
It is a documentation fragment, not a replacement for the full config. Neither example is loaded automatically.
Once authentic samples exist, insert only verified incoming patterns into the actual config array.
Named groups mean future changes to server phrasing do not require changes to `GambleManager`.

Receiving a system-channel message is not proof of a financial transaction. Some servers forward player
text as system messages. Authentic samples must establish strict prefixes/context separating payments
from player chat, outgoing confirmations, errors, balances and broadcasts. An overly broad user-supplied
pattern cannot make untrusted messages authentic. No current claim of DonutSMP detection accuracy is made.

## Parsing and money

`MoneyParser` accepts `100`, `1,000`, `1000.50` and `1,000.50`. Commas must form groups of three;
decimal fractions have at most two digits. It rejects signs, zero, exponent notation, NaN/infinity,
suffixes, spaces inside numbers, extra arguments, malformed grouping and values above 1 billion.
Usernames must match `[A-Za-z0-9_]{3,16}` and must not be the local username (case-insensitive).
Structured receipts validate those constraints again. Each receipt includes a SHA-256 fingerprint
of the normalized message in its existing `receiptId` field.

Actual received amounts are parsed directly into `BigDecimal`, without passing through a double.
Payout is `received × BigDecimal.valueOf(config.payoutMultiplier)`, including the full multiplier
and original stake: 100 × 2 = 200, 250 × 1.5 = 375. Round once to two decimal places, HALF_UP.
`AmountFormatter` strips trailing zeros and produces plain notation. The BigDecimal overload supports
payouts up to 1 trillion; the existing double advertising overload retains its 1 billion bound.
Payouts that round to zero or exceed the safety bound are rejected before any roll.
Fractional server acceptance and the server's actual precision/limits still require verification.

## Receipt arbitration and randomness

Flow: received message → `PaymentParser` → structured receipt → `ReceiptDeduplicator` →
`OutgoingPaymentTracker` conflict check → bet bounds and queue capacity → one independent roll →
loss or fixed winner job → `WinnerPayoutProcessor` → shared `MinecraftPaymentDispatcher`.

Deduplication keys use case-insensitive sender plus canonical amount. This deliberately merges alternate
formatting and different incoming patterns for the same payment, even when their raw fingerprints differ.
The first observation opens a short monotonic-time window; duplicates do not extend it. After two seconds
by default, the same sender/amount can be accepted again. Thus payments at 12:00:01 and 12:00:10 are distinct.
Both system and overlay events share the same cache. Parsed receipts observed while disabled are remembered
briefly but never rolled or buffered for later gambling.

Without an authoritative server transaction ID, two legitimate identical payments inside the window
cannot be distinguished from two copies of one payment. This implementation conservatively suppresses
the second. Conversely, a delayed replay outside the window can be accepted. Authentic transaction IDs,
if available later, should replace this heuristic. The cache is bounded to 4096 entries, expires on ticks
and fails closed when full rather than evicting live protection records.

Each accepted bet calls one persistent injectable random generator's `nextDouble()` exactly once and
wins when the result is less than `winChance`. Zero always loses; one always wins. Invalid/out-of-range
bets, conflicts, duplicates, disabled receipts and capacity failures do not roll. The initial invalid-bet
policy is IGNORE (no refund); an injected `InvalidBetPolicy` is the extension point. Relevant outcomes
are logged with `[AutoGamble]`; unparsed chat is not logged.

## Outgoing protection and dispatch arbitration

Both advertising and winner commands go through the same send method. Before invoking the vanilla
`ClientPacketListener.sendCommand`, the dispatcher records target, exact formatted amount, monotonic
timestamp and source (`ADVERTISING` or `GAMBLE_PAYOUT`). A candidate incoming receipt matching that
target/amount during the outgoing window is rejected, independently of local-username checks.
Records also cover ambiguous command failures and are not consumed by the first echo. They expire after
ten seconds by default and have a bounded 4096-record capacity; no untracked command is sent if full.

This intentionally also suppresses a real same-amount return payment from that target inside the window.
It protects against confusing recently sent transactions with incoming money; it cannot classify late
echoes or server fee/amount transformations without authentic patterns. It tracks mod-sent payments,
not manual commands typed by the user. Patterns themselves must only describe incoming transactions.

`DispatchGate` enforces at most one payment attempt per client tick across both systems. Due winners
are considered first; advertising keeps its existing randomized schedule and unpaid-player policy.
If a due advertisement cannot use the tick, that attempt takes a fresh normal advertising delay.
No custom packets or server plugin are used. A successful local API call is **not** server acceptance.

## Payout FIFO, failures and lifecycle

Up to 256 fixed winner jobs are retained in a FIFO. Capacity is checked before accepting/rolling a bet;
when full, additional bets are explicitly ignored and logged, never rolled then silently discarded.
Each head gets a freshly sampled inclusive delay from `winnerDelayMinimumMs`–`winnerDelayMaximumMs`
(200–700 ms by default). Later jobs do not inherit deadlines from receipt arrival or drain in a burst.
Roll outcomes and payout amounts are frozen at acceptance; changed settings affect future bets and waits.

The dispatcher verifies the live connection, player, world, client thread, feature flags, target syntax,
current TAB presence and absence of screens/overlays immediately before sending. Screen or unavailable
target failures retain the FIFO head and retry no sooner than 500 ms. An offline head can therefore hold
up later winners until it reappears; this is the explicit initial FIFO policy. No expiry or silent drop of
those jobs occurs while the active session remains enabled.

After the vanilla command API is invoked, an exception is an **uncertain** outcome: log it and remove
the job without retrying, since automatic retries might double-pay. No server rejection/insufficient-funds
parser exists yet; a returned API call consumes the job even if the server later rejects the command.
These outcomes need manual reconciliation until authentic server response parsing is available.

Disabling the master or gambling switch cancels queued payouts and logs their count. Receipt/outgoing
protection caches survive a toggle until their normal expiry, preventing an immediate replay bypass.
Disconnect, world/server change, title-screen return and shutdown cancel jobs/deadlines and clear both
caches plus paid-player history. No old payout is sent after reconnecting; there is no disk queue or replay.
Cancellation does not refund bets or settle outstanding winners. That remains an explicit limitation
of the requested transient session design. All state and network work stays on the client thread.

## Classes and verification

Added: `RegexPaymentParser`, `ReceivedMessage`, `MoneyParser`, `ReceiptDeduplicator`,
`OutgoingPaymentTracker`, `PaymentSender`, `DispatchGate`, `WinnerPayoutProcessor`.
Changed: `AutoGambleClient`, `AutoGambleConfig`, `ConfigManager`, `PaymentParser`, `GambleManager`,
`PaymentQueue`, `MinecraftPaymentDispatcher`, `AmountFormatter`. The Stage 2 `AutoPayManager` and
`PlayerSelectionManager` logic remains unchanged. No final GUI was added.

The full suite has 60 tests (26 prior-stage tests plus 34 Stage 3 tests), covering synthetic parsing,
malformed input, normalization, money, bounds, probability endpoints, exactly-one-roll behavior,
deduplication and expiry, both outgoing sources, queue ordering, delays, retries, cancellation,
arbitration, config validation and Stage 2 migration. Tests use injected randomness and clocks,
not DonutSMP connections. The prior migration test now expects schema 2 and treats schema 3 as future.

Authentic DonutSMP incoming, outgoing, error and repeated-payment samples are still required before
production pattern activation. Live hook provenance/rendering, target-online checks, server amount
acceptance, screen interaction, reconnect behavior and actual command acceptance were not live-tested.
The build and independent logic tests are verified; no real payments were made during development.
