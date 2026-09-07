# AutoGamble 1.2.0

This update extends the existing mod. Minecraft **26.2**, Java **25**, Fabric Loader **0.19.3**, Fabric API **0.159.0+26.2**, Loom and Gradle versions remain unchanged.

## Customer follow

Auto Follow Good Customers defaults **OFF**. Its default threshold is **5,000,000 (5M)**, using cumulative validated incoming payments from each customer. Settings accept plain money and K/M/B/T suffixes, including `5m`, `5M`, `5000000` and `1.5m`. Zero is allowed for the follow threshold.

When enabled, eligible customers receive one `/follow <username>` command. A real successful dispatch is recorded in `config/autogamble/followed_players.txt`, one display username per line. Comparisons use `Locale.ROOT` lowercase. Restart, reconnect and server switch do not clear it. Invalid lines are ignored and duplicate names are normalized. The confirmation-protected clear button only clears followed history, leaving totals intact.

Dry Run logs `[AutoGamble] DRY RUN: Would follow Bob` without sending a command or adding to permanent history. Simulated follow logs are suppressed for 30 seconds per player. Ambiguous command errors are suppressed in the current process and logged rather than retried immediately. Failed followed-file writes are retried; further follows wait while that history cannot be saved.

## Reports and durable data

Paths are relative to the Minecraft game directory (the directory containing `config/`):

- `config/autogamble/followed_players.txt`
- `config/autogamble/reports/payments_to_players.txt`
- `config/autogamble/reports/top_customers.txt`
- `config/autogamble/reports/recent_payments.txt`
- `config/autogamble/data/payment_totals.json`
- `config/autogamble/data/balance_rule_state.json`

Totals and retained transactions are stored together in `payment_totals.json`, so a single atomic replacement commits both. BigDecimal amounts are serialized as strings, and display names are stored separately from normalized keys. Rules themselves live in the existing `config/autogamble.json`; the rule-state file stores re-arm/cooldown state. Existing payer history stays at `config/autogamble-payers.json`.

Reports default ON. Paid and received totals sort largest first, with one player per line and no zero totals. Recent reports default to newest first, both directions, minimum zero, unlimited maximum, and 1,000 lines. Each transaction occupies exactly one line. Inclusive money filters and line/order changes regenerate from retained history. Turning a report OFF leaves its existing file untouched.

Stored history defaults to 10,000 transactions, configurable from 100 to 1,000,000. Pruning old transactions never reduces aggregate totals. Report line limits range from 1 to 100,000. All history loading, streaming JSON persistence, sorting, and report writes run on one background worker. Updates are coalesced at approximately 500 ms, and shutdown flushes pending work. Files use UTF-8 and temporary-file replacement. Corrupt structured data is logged and backed up before replacement; backup failures protect the original file from overwriting.

Incoming accounting uses validated incoming notices, excludes parser duplicates and outgoing echoes, and remains active when gambling is disabled. A second accounting deduplication window prevents mode/session resets from counting a repeated receipt again. Actual incoming payments are still real in Dry Run. Outgoing accounting records successful real command dispatches for advertising, winner payouts and balance rules; it excludes simulations and ambiguous sends. A dispatch is not a server-side confirmation of payment acceptance. Manual outgoing payments are not inferred. Historical payments from before this upgrade are not invented.

## Balance Payment Rules and current limitation

Automatic Balance Payments defaults **OFF**, with an empty rule list. Up to 100 rules can be added, edited, enabled, disabled or deleted. Each has a username, positive BigDecimal threshold and amount, and cooldown (default 30 seconds, allowed 0–86400). Amounts retain the existing dispatch limit of 1T. Invalid names, negative/zero values, exponents and malformed suffixes are rejected.

The rule engine triggers once at or above the threshold, then disarms. Only an observed balance below the threshold re-arms it; the cooldown must also expire. Disarmed/cooldown state survives normal restarts. Changing a previously observed rule's target/amount/threshold requires a new below-threshold observation before it can fire. Successful or ambiguous attempts invalidate the balance observation, preventing another rule from reusing stale funds. Other real outgoing commands and session changes also invalidate known balance. Observations expire after five seconds. Real rule payments use the existing dispatch gate, outgoing tracker and central accounting callback.

**No reliable current-balance source exists in the inspected project. No DonutSMP scoreboard, action-bar or chat balance format was verified. Therefore this release does not populate a live balance value and cannot trigger real balance-rule payments on DonutSMP yet.** Status reports `Current Known Balance: UNKNOWN`. `KnownBalance.observeVerified` is the adapter boundary for future verified server observations, not a manual estimate or transaction-arithmetic tracker. Test observations are confined to tests.

Dry Run never sends rule payments and never permanently disarms the real rule. It logs simulations with a 30-second suppression; switching to live mode can trigger when a fresh, verified balance qualifies. Existing master switch, client-thread checks, connection checks, blocked-input checks, self-payment checks and one-command-per-tick gate still apply.

## GUI and commands

Open F9 (or the existing settings command), then **General → Customers, Reports & Balance…**.

- Good Customer Follow: master toggle, threshold, confirmed history reset.
- Payment Report Files: three report toggles, recent direction/money filters, sort order, line limit, retention.
- Balance Payment Rules: master toggle, paged rule list, add/edit/delete and per-rule enable toggle.

Subscreens share the original unsaved draft. Save & Done applies it. Back preserves draft edits. Existing Dry Run-to-live confirmation remains in place.

`/autogamble status` includes follow state/threshold/count and balance master/rule counts/known balance/source. `/autogamble reports status` lists report directory, enabled reports and data counts. `/autogamble reports refresh` schedules regeneration of enabled reports.

## Migration and verification

Config schema **6** preserves valid 1.1.x values, including Auto Pay, gambling, first-time bonus, spam warnings and custom regex patterns. New automatic command systems default OFF; all three report outputs default ON.

The offline Minecraft UI smoke test passed all 9 existing settings pages and 7 new pages, checking widget bounds and overlap at 320×240 and the actual development window size. Screenshots of the new pages were inspected. The temporary smoke driver is excluded from the final production build. No server was joined and no live payments or follows were sent.

Build: `gradlew.bat test` and `gradlew.bat build` (or `sh gradlew` on other platforms). Production artifact: `build/libs/autogamble-1.2.0.jar`.

Final verification: **294 tests passed, 0 failures, 0 errors, 0 skipped**. `gradlew.bat clean test` and `gradlew.bat build` succeeded. The release JAR contains only the production client entrypoint, no smoke-test classes, and Java class version 69 (Java 25).

JAR SHA-256: `2696abd749e6f575b7b6cd535271004a3a970616589b5948102fb4b0a97142a1`.

On this PC the artifact is `C:/Users/jonsm/Desktop/FreakCam/AutoGamble/build/libs/autogamble-1.2.0.jar`. The offline development instance writes its data beneath `C:/Users/jonsm/Desktop/FreakCam/AutoGamble/run/config/autogamble/`. Installed Minecraft instances use their own game directory with the same relative paths listed above.
