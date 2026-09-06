# AutoGamble 1.0.5

A client-only Fabric mod with randomized command-suggestion auto-pay, configurable incoming-payment gambling,
and Minecraft-style in-game settings. **Dry Run Mode is ON by default.**

The confirmed incoming DonutSMP format **`<player> paid you $ <amount>`** is supported by an enabled
built-in pattern. **Use Dry Run first for live testing.** The incoming wording was confirmed by the
user's authentic examples; outgoing confirmations and error formats have not been verified.

## Requirements and installation

| Component | Pinned version |
| --- | --- |
| Minecraft | **26.2** |
| Java | **25** |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.159.0+26.2 |
| Loom | 1.17.20 (`net.fabricmc.fabric-loom`) |
| Gradle wrapper | 9.5.1 |

Minecraft 26.2 is unobfuscated: this project has no mappings dependency. Loader is now 0.19.3;
Minecraft, Java, Fabric API, Loom and Gradle versions are otherwise unchanged.

1. Install Fabric Loader for Minecraft 26.2 and use Java 25.
2. Put `autogamble-1.0.5.jar` and the matching Fabric API JAR in your client's `mods` directory.
3. Remove earlier AutoGamble JARs from that directory so only one version loads.
4. Launch Minecraft. No server installation, plugin or custom network protocol is required.
5. Open settings and start with Dry Run Mode ON. Auto Pay and Gambling are OFF by default.

## Settings, commands and keys

| Input | Action |
| --- | --- |
| F8 | Toggle AutoGamble |
| F9 | Open AutoGamble Settings |
| `/settings Gamble` | Open AutoGamble Settings locally |
| `/autogamble settings` | Settings alias |
| `/autogamble status` | Show current runtime state locally |

The Gamble argument is case-insensitive (`gamble`, `GAMBLE` also work). The exact `/settings Gamble`
command is intercepted with Fabric's `ClientSendMessageEvents.ALLOW_COMMAND` and cancelled before
sending. The screen opens on the next client tick, after chat closes. No `/settings` root is registered,
so unrelated commands such as `/settings SomethingElse` pass through unchanged. The `/autogamble`
tree uses `ClientCommandRegistrationCallback`. Intercepted `/settings Gamble` has no custom autocomplete.

Both keybindings use Minecraft's standard rebindable system: Options → Controls → Key Binds → AutoGamble.
The settings screen's Keys section opens that same screen. There is no custom key-capture mechanism.
Standard keybinding edits apply immediately through Minecraft, independently of the AutoGamble draft.

The settings pages are General, Auto Pay, Gamble, Timing, Advanced and Keys. They use standard Minecraft
buttons, edit boxes, a win-chance slider (0.1 percentage-point steps), tooltips and confirmation screens.
Pages keep the controls usable without one long scrolling form. The General page shows runtime counts,
refreshed once per second, alongside the draft's ON/OFF switches.

Changes are an **unsaved draft** until **Save & Done**. Cancel or Escape discards that draft. Moving
between pages, resizing, opening parser tools or visiting Key Binds preserves edits. Numeric errors
disable Save and display an explanation; invalid text is never written to config. Paid-history reset is
an explicit immediate action with confirmation, even if you later cancel other settings edits.

## Dry Run Mode

Dry run exercises selection, timers, parsing, rolls and winner queues but sends **no `/pay` command**.
Both advertising and winner payouts pass through `PaymentExecution`, the final shared guard immediately
before the only real command callback. Simulated commands advance history and queue state and are logged
as `DRY RUN`. They do not register imaginary outgoing transactions that could suppress real incoming tests.

Changing from dry run to real payments through Save shows **Enable real payments?** with
**Enable Real Payments** and **Cancel**. Approval is saved; it is not requested each launch.
Cancelling leaves the live configuration unchanged. Editing `dryRunMode` manually in JSON is an explicit
external configuration choice; the confirmation belongs to the in-game Save flow.

Switching either way between dry and real mode clears pending payouts, resets the advertising deadline,
paid history and simulated receipt state. Simulated winners can never become live payouts later.
Recent real outgoing-payment records retain their normal expiry protection.

## Auto Pay

Auto Pay starts a fresh discovery cycle when its payment timer expires. Each request independently
chooses a uniformly random length between `minimumPrefixLength` and `maximumPrefixLength` (defaults
1 and 3), then generates that many independent random lowercase a–z characters. It requests
`/pay <prefix>` through vanilla command suggestions, never empty-prefix or TAB fallback. Only matching
server-returned names are eligible; a separate persistent RNG selects the payment recipient.

Open Auto Pay → Prefix Length to edit Minimum Prefix Length and Maximum Prefix Length. Both must be
whole numbers in 1–3, with maximum at least minimum. Invalid drafts cannot be saved. GUI changes reset
pending discovery and apply without restarting. Missing config fields acquire defaults without replacing
existing settings; config schema remains 4.

Empty or paid-only responses retry after 200 ms with a fresh random length and fresh letters. Exact
prefix duplicates are resampled within the cycle. After 10 requests, no viable results skip the payment
and start the normal configured delay. Unanswered requests time out after 3 seconds before retrying.
A bounded resampling guard also fails safely with pathological injected randomness. Tried-prefix state
clears on selection, exhaustion or session reset. Paid history remains shared across prefixes; the
existing known-result cycle-reset policy applies only after the complete search is exhausted.

**Skip Numeric-Only Names** remains ON by default. Failed targets, self and duplicate names remain
excluded. Dry Run remains ON by default and never sends a real payment command.

The exact server notice `That player does not exist` blacklists the most recent real advertising target
for 10 minutes, only when received within 3 seconds of dispatch. This is conservative attribution, not a
transaction-ID match. Winner dispatches and manual commands clear the attribution slot. Player-authored
chat cannot blacklist targets. The blacklist is never saved and clears on server/world/session changes.
Dry-run commands do not create real-error attribution.

The configured amount is used, not a hardcoded $1. Amounts are formatted to at most two decimals.
With Prefer Unpaid Players ON, each selection prefers names not yet paid in the current cycle. Once all
currently eligible players have been paid, history clears for a fresh randomized cycle. New arrivals
naturally become unpaid candidates; departed players are not selected from history. With the option
OFF, random repeat selections are allowed.

A fresh fractional-second delay between the configured minimum and maximum is sampled at activation
and after each attempted payment or empty-list check. Monotonic deadlines run on client ticks, with
approximately 50 ms granularity at normal tick rate. There are no background timers or catch-up bursts.
Delay edits affect the next scheduling cycle. Disabling/re-enabling starts a fresh wait.

All open screens and overlays postpone due payments in 500 ms increments, including chat, command entry,
settings, Key Binds, signs and books. No per-tick TAB scanning occurs while waiting. The mod is not tied
to a particular server address; enabled systems operate in the current connected session.

## Gambling and winner payouts

Received system/overlay messages use `ClientReceiveMessageEvents.GAME`. Profileless server chat also
uses `ClientReceiveMessageEvents.CHAT` (both signed-message and sender profile must be null). Known
player-authored chat is rejected to prevent typed payment notices becoming bets. Neither hook changes
or cancels rendering. Both feed the same parser and receipt cache, so duplicate deliveries roll once.
The parser matches the whole normalized visible text, including `JonsmanV6or7 paid you $ 19.8k` → $19800.
Minecraft Component styles do not affect `getString()`. User-defined patterns retain bounded matching;
the constrained built-in grammar is evaluated independently of elapsed-time budgets.

An accepted incoming bet rolls once against `winChance` (0–1 internally). A winner receives the full
`received amount × payoutMultiplier`: $100 × 2 = $200. The stake is not subtracted. Actual received money
is parsed directly as `BigDecimal`, multiplied using decimal arithmetic and rounded once HALF_UP to cents.
Bet limits are inclusive; out-of-range bets are ignored and logged, without automatic refunds.

Winners enter a bounded FIFO with a new inclusive random delay before each head (default 200–700 ms).
Winner work is considered before advertising. A shared dispatch gate allows at most one payment attempt
per tick across both sources. A winner is not dropped because advertising was due.

Disconnected or screen-blocked clients preserve the head and retry no sooner than 500 ms.
A validated winner no longer has to appear in TAB; the server determines whether the payment is accepted. Ambiguous send failures are logged and not retried, because retrying could double-pay.
A successful local API call is not proof of server acceptance or sufficient funds. A full 256-job queue
causes new bets to be ignored before rolling. Disabling the master or gambling switch clears queued
payouts and logs the count. Cancellation is not a refund or settlement of outstanding winners.

## Parser setup and testing

1. Start with Dry Run ON. The confirmed DonutSMP incoming pattern is already available.
2. If additional formats are needed, edit `incomingPaymentPatterns` in `config/autogamble.json`.
3. Open Advanced → Parser Setup & Test → **Import Patterns from JSON**. Only the pattern array is
   imported into the draft; no real-payment flags or other settings are imported.
4. Paste an exact message into **Test Payment Message** and press **Test Parser**.
5. Review `No pattern matched` or the matched sender and amount, then Save & Done on the main screen.

No restart is needed for import/Save. Imports reject invalid regex or missing named groups. Parser tests
use the draft patterns and only return a result: they have no gamble manager, receipt cache, queue or
dispatcher references. They never roll, gamble or send payments, even when live mode is enabled.
The [disabled examples](docs/incoming-patterns.example.json) illustrate the custom-pattern JSON shape.

### Confirmed incoming format and suffixes

The exact built-in regex is:

```regex
^(?<sender>[A-Za-z0-9_]{3,16})\s+paid\s+you\s+\$\s*(?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?[kKmMbBtT]?)$
```

The existing 3–16 character Java username validation is retained. The message must match entirely,
including `paid you` and `$`. Plain visible text normalization handles colors and whitespace; exact
Minecraft style/color objects are not required. Signed player chat remains excluded.

| Suffix (case-insensitive) | Multiplier |
| --- | --- |
| K | thousand: 1,000 |
| M | million: 1,000,000 |
| B | billion: 1,000,000,000 |
| T | trillion: 1,000,000,000,000 |

Examples: `$ 1k` → $1,000; `$ 1M` → $1,000,000; `2.5K` → 2500; `1.25B` → 1250000000.
Plain integers, decimals and correctly grouped commas still work. Parsing uses only BigDecimal;
suffixes are expanded before bet limits, deduplication, outgoing tracking and payout calculations.
The parser-test screen shows the expanded numeric value. Unknown suffixes, repeated suffixes, signs,
exponents and malformed decimal/grouping tokens are rejected.

Incoming parsing is bounded at one trillion, while the existing configurable bet ceiling remains
one billion and the default maximum bet remains one million. Thus `2M` parses correctly but is ignored
at the default bet limit. The existing payout cap remains one trillion. Supporting a token does not
automatically make it an accepted bet.

`donutSmpIncomingEnabled` defaults true and controls the built-in separately from the custom-pattern
array. Migration never inserts into or replaces that array. An exact custom copy of the built-in regex
takes precedence (including if disabled); duplicate regex text is compiled once. Other custom patterns
remain in their existing order. Equivalent differently written patterns cannot cause multiple rolls:
the parser returns only its first match, followed by the shared receipt deduplicator.

System-message delivery does not authenticate a financial transaction: servers may forward player text
as system messages. A broad regex cannot make such text trustworthy. Verified message structure and
negative examples are essential before activation.

## Duplicate and outgoing protection

Case-insensitive sender plus canonical amount identifies a receipt for a short window (default 2 seconds).
Alternate formatting/channels share this cache; duplicates do not extend expiry. Identical bets several
seconds apart are allowed. Without server transaction IDs, two legitimate identical bets within the
window can be suppressed, and late replays beyond the window cannot reliably be recognized.

Before every real mod-sent payment, outgoing tracking records target, formatted amount, timestamp and
source (ADVERTISING or GAMBLE_PAYOUT). Matching incoming candidates are suppressed for 10 seconds by
default. This is independent of local-name exclusion and can also suppress a legitimate same-amount
return payment during that window. Manual payments are not tracked. Exact echo/error semantics still
require verified server samples. Caches are bounded and expire; they are never permanent.

Disconnect, world/server change, title-screen return and shutdown clear all pending jobs and transient
session state. Payments never cross servers. Normal enable toggles retain recent receipt/outgoing
protection until expiry. Settings and networking remain on Minecraft's client thread.

## Configuration

Stored at **`config/autogamble.json`**, relative to the Minecraft game directory. Schema version is **4**.
Existing files migrate without losing their values; missing `dryRunMode` and `donutSmpIncomingEnabled` default to true.
Malformed files retain the existing backup/default recovery. Future schemas are read-only and disabled.
Disk write failures are logged and shown in the UI; valid settings may remain active in memory.

| Setting | Default |
| --- | --- |
| configVersion | 4 |
| donutSmpIncomingEnabled | true |
| dryRunMode | true |
| enabled | true |
| autoPayEnabled / gambleEnabled | false / false |
| autoPayAmount | 1 |
| minimumAutoPayDelaySeconds / maximumAutoPayDelaySeconds | 2.0 / 5.0 |
| preferUnpaidPlayers | true |
| winChance / payoutMultiplier | 0.50 / 2.0 |
| minimumBet / maximumBet | 1 / 1,000,000 |
| winnerDelayMinimumMs / winnerDelayMaximumMs | 200 / 700 |
| incomingPaymentPatterns | empty custom array; built-in DonutSMP pattern is separate |
| receiptDeduplicationWindowMs | 2000 |
| outgoingPaymentTrackingWindowMs | 10000 |

GUI validation rejects invalid text and inverted ranges instead of silently clamping. Bounds: auto-pay
amount $0.01–1 billion, pay delays 0.001–86400 seconds, probability 0–100%, multiplier 0.001–1000,
bets 0–1 billion, winner delays 0–86400000 ms, dedup 100–60000 ms and tracking 1000–120000 ms.
Config-file recovery retains its tolerant bounded validation. Patterns support up to 16 entries of
512 characters each, with bounded matching work. Currency precision is two decimal places; server
acceptance of fractional values and actual currency limits remain unverified.

## Build and verification

Set `JAVA_HOME` to JDK 25. From the project directory:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

On macOS/Linux use `sh gradlew test` and `sh gradlew build`.
The installable artifact is **`build/libs/autogamble-1.0.5.jar`**; the sources JAR is for development.
The project ZIP includes the Gradle wrapper, sources, tests, metadata, translations and documentation.

The suite contains 209 tests, retaining prior coverage and adding 26 first-payer tests. Coverage includes dry-run dispatch,
migration, strict validation, live configuration policy, command routing, safe parser testing,
pattern import, confirmation policy and winner priority. No test connects to DonutSMP.

The main additions are `AutoGambleSettingsScreen`, `AdvancedParserScreen`, `WinChanceSlider`,
`SettingsContext`, `SettingsDraft`, `SettingsValidation`, `RuntimeSettingsChange`,
`SettingsCommandRouter`, `PatternConfigService`, `ParserTestService` and `PaymentExecution`.
Existing managers, queue, dispatcher, config persistence and keybinding registrations are reused.

The confirmed incoming wording is implemented. Outgoing/error wording, real payment acceptance and
live multi-player behavior remain unverified. B/T are explicitly supported as requested, but only the
plain/K/M incoming examples were supplied as observed samples. No real payments have been made during
development. See [the 1.0.5 patch report](docs/PATCH-1.0.5.md) for verification details.

## Live diagnostics

Use `/autogamble status` to see global/auto-pay/gamble flags, Dry Run, current player source and
candidate count, paid history, next deadline, screen/connection blocks, last auto-pay state, parser
state, last parsed receipt/result and pending payouts. Close screens to let payment timers dispatch.
Dry Run deliberately sends nothing, but logs simulated advertising and gamble results.

`/autogamble debug on` logs the next 100 received messages with channel and normalized text to
`logs/latest.log`, and shows parsed outcomes locally. `/autogamble debug off` disables diagnostics.
It is OFF by default and is not persisted. Logs can contain chat text; share only the relevant lines.
If nothing parses, check that global/Gambling and the built-in parser are enabled, the sender isn't
your own account, and the amount falls within bet limits. Manual config changes require restarting
or the existing parser import workflow; GUI Save applies immediately.

The previous code observed only GAME, and dispatch required TAB membership even for winners.
These are verified limitations in code, not proof of the exact cause on DonutSMP. The assistant has
not joined DonutSMP: actual receive channel, autocomplete scope, server acceptance/balance/errors,
and signed/player-authored routing still require a live test. Dry Run remains ON by default.

### 1.0.5 status additions

`/autogamble status` reports Last Auto Pay Prefix, Last Candidate Count, Last Selected Player,
Numeric-Only Filter and Failed Target Blacklist count. Active discovery source is
`RANDOM_PREFIX_SUGGESTIONS`. The older 20-second empty-prefix cache is no longer used by the runtime.
No live DonutSMP payment or autocomplete response was tested during this patch; local tests simulate
server suggestions and verify exact dry/live dispatch behavior. Minecraft 26.2, Java 25 and Loader 0.19.3
remain unchanged. Config schema remains 4: missing numeric-filter settings acquire the true default
without replacing existing values or parser patterns.

### 1.0.5 prefix status

Status additionally reports Prefix Length Range, Last Prefix Length, Last Auto Pay Prefix, and
Prefix Attempts This Cycle, alongside existing candidate/source/blacklist fields. Attempts reset to
zero when the search finishes. Legacy fixed-length constructors exist for compatibility tests only;
the live dispatcher exclusively uses the configurable range constructor with a ten-request limit.

## First-time payer advantage (1.0.5)

Under Gamble → First-Time Payer Bonus, enable/disable the feature (default ON) and adjust First-Time
Win Bonus (default +10.0 percentage points; range 0–100, steps of 0.1). Internal config fields are
`firstTimePayerBonusEnabled=true` and `firstTimeWinBonus=0.10`. Missing fields acquire defaults while
existing settings remain intact; schema 4 is retained for these additive compatible fields.

A new payer's effective chance is base chance plus bonus, capped at 100%. For example 39.4% + 15 points
is 54.4%, and 90% + 20 points is 100%. Payout multipliers and amounts do not change. The base-odds
regression tests explicitly turn the optional bonus off; first-time behavior has separate tests.

Only an accepted bet that reaches its single roll consumes first-time status, on both win and loss.
Malformed, duplicate, outgoing, disabled, out-of-range and capacity-rejected bets do not consume it.
The bonus check, roll and history insertion occur sequentially on the client thread. Distinct rapid
bets from the same username therefore receive the bonus only once. Dry Run incoming bets DO consume
first-time status; parser-test messages still do not roll or record payers. Bets with the bonus disabled
also record the payer, because they remain accepted bets.

History is separate from config in `config/autogamble-payers.json`, version 1, containing lowercase
Locale.ROOT usernames. It loads on startup and saves each new payer through a temporary file and atomic
replacement where supported. Disconnects, world switches, mode changes and normal config migrations
never clear it. It applies across servers, as requested for a player's first-ever accepted bet.
Disk failures are logged; current in-memory state remains. Persistence across restarts requires writable
storage. Corrupt data is backed up with a .corrupt timestamp suffix and recovered as empty history, so
unknown prior payers may regain the bonus after corruption.

The bonus settings include Reset First-Time Payer History with explicit Reset/Cancel confirmation.
A successful reset is immediate and persistent, independently of unsaved settings. A failed reset save
restores the in-memory history and shows an error. Status reports base chance, bonus switch/value and
known-payer count, never the full name list. Real DonutSMP transactions were not performed in development.
