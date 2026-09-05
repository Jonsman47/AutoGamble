# AutoGamble 1.0.0

A client-only Fabric mod with randomized TAB auto-pay, configurable incoming-payment gambling,
and Minecraft-style in-game settings. **Dry Run Mode is ON by default.**

**Incoming DonutSMP payment regex must be configured using an authentic server payment message before
live gambling can work.** No authentic format has been supplied or verified. The included example
patterns are disabled documentation examples, not confirmed DonutSMP support.

## Requirements and installation

| Component | Pinned version |
| --- | --- |
| Minecraft | **26.2** |
| Java | **25** |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.159.0+26.2 |
| Loom | 1.17.20 (`net.fabricmc.fabric-loom`) |
| Gradle wrapper | 9.5.1 |

Minecraft 26.2 is unobfuscated: this project has no mappings dependency. All toolchain versions are
unchanged from the working Stage 1–3 builds.

1. Install Fabric Loader for Minecraft 26.2 and use Java 25.
2. Put `autogamble-1.0.0.jar` and the matching Fabric API JAR in your client's `mods` directory.
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

Auto Pay requires both the master switch and Auto Pay to be ON. It selects random valid profiles from
the client-visible listed TAB entries, excludes the local player and malformed/duplicate names, and
rechecks the target before dispatch. Client-visible data cannot reliably identify a server-created fake
profile that looks exactly like a real player.

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

Received system/overlay messages are observed using `ClientReceiveMessageEvents.GAME`, without modifying
chat rendering. Signed player chat is not subscribed to. Enabled incoming regex patterns must match the
whole normalized message and capture named groups `sender` and `amount`. Usernames and money are validated
again after matching. Negative/zero amounts, malformed grouping, exponent notation and suffixes are rejected.

An accepted incoming bet rolls once against `winChance` (0–1 internally). A winner receives the full
`received amount × payoutMultiplier`: $100 × 2 = $200. The stake is not subtracted. Actual received money
is parsed directly as `BigDecimal`, multiplied using decimal arithmetic and rounded once HALF_UP to cents.
Bet limits are inclusive; out-of-range bets are ignored and logged, without automatic refunds.

Winners enter a bounded FIFO with a new inclusive random delay before each head (default 200–700 ms).
Winner work is considered before advertising. A shared dispatch gate allows at most one payment attempt
per tick across both sources. A winner is not dropped because advertising was due.

Blocked/offline targets preserve the head and retry no sooner than 500 ms; an offline head can hold up
later winners. Ambiguous send failures are logged and not retried, because retrying could double-pay.
A successful local API call is not proof of server acceptance or sufficient funds. A full 256-job queue
causes new bets to be ignored before rolling. Disabling the master or gambling switch clears queued
payouts and logs the count. Cancellation is not a refund or settlement of outstanding winners.

## Parser setup and testing

1. Obtain authentic incoming and outgoing/error payment messages from the server.
2. Edit `incomingPaymentPatterns` in `config/autogamble.json` using strict incoming-only patterns.
3. Open Advanced → Parser Setup & Test → **Import Patterns from JSON**. Only the pattern array is
   imported into the draft; no real-payment flags or other settings are imported.
4. Paste an exact message into **Test Payment Message** and press **Test Parser**.
5. Review `No pattern matched` or the matched sender and amount, then Save & Done on the main screen.

No restart is needed for import/Save. Imports reject invalid regex or missing named groups. Parser tests
use the draft patterns and only return a result: they have no gamble manager, receipt cache, queue or
dispatcher references. They never roll, gamble or send payments, even when live mode is enabled.
The [disabled examples](docs/incoming-patterns.example.json) illustrate the JSON shape only.

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

Stored at **`config/autogamble.json`**, relative to the Minecraft game directory. Schema version is **3**.
Existing Stage 1–3 files migrate without losing their values; the missing `dryRunMode` defaults to true.
Malformed files retain the existing backup/default recovery. Future schemas are read-only and disabled.
Disk write failures are logged and shown in the UI; valid settings may remain active in memory.

| Setting | Default |
| --- | --- |
| configVersion | 3 |
| dryRunMode | true |
| enabled | true |
| autoPayEnabled / gambleEnabled | false / false |
| autoPayAmount | 1 |
| minimumAutoPayDelaySeconds / maximumAutoPayDelaySeconds | 2.0 / 5.0 |
| preferUnpaidPlayers | true |
| winChance / payoutMultiplier | 0.50 / 2.0 |
| minimumBet / maximumBet | 1 / 1,000,000 |
| winnerDelayMinimumMs / winnerDelayMaximumMs | 200 / 700 |
| incomingPaymentPatterns | empty array |
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
The installable artifact is **`build/libs/autogamble-1.0.0.jar`**; the sources JAR is for development.
The project ZIP includes the Gradle wrapper, sources, tests, metadata, translations and documentation.

The suite contains 80 tests: all 60 prior-stage tests plus 20 Stage 4 tests for dry-run dispatch,
migration, strict validation, live configuration policy, command routing, safe parser testing,
pattern import, confirmation policy and winner priority. No test connects to DonutSMP.

The main additions are `AutoGambleSettingsScreen`, `AdvancedParserScreen`, `WinChanceSlider`,
`SettingsContext`, `SettingsDraft`, `SettingsValidation`, `RuntimeSettingsChange`,
`SettingsCommandRouter`, `PatternConfigService`, `ParserTestService` and `PaymentExecution`.
Existing managers, queue, dispatcher, config persistence and keybinding registrations are reused.

Authentic DonutSMP message patterns, real payment acceptance and live multi-player behavior are still
unverified. No real payments have been made during development. See the final build report for the
local UI smoke-test result.
