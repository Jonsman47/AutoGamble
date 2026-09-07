# AutoGamble 1.1.0 â€” recovered source

Recovered on 2026-09-07 from the user-supplied `autogamble-1.1.0.jar`, using the v1.0.5 source at commit `689d46765a14b8722164812ac511cae636fd927d` as the baseline.

## Changes recovered

- Payment-spam tracking by player, case-insensitively: default 3 payments within 10 seconds, 60-second warning cooldown.
- Queued `/msg` warnings, retried at most every 500 ms when dispatch is blocked; pending warnings expire after 30 seconds. Tracks at most 4096 players and 256 pending warnings.
- Warnings share the command dispatch gate with payments. Winner payouts run before warnings, which run before advertising payments.
- Dry Run logs instead of sending messages. Disabling gambling, warnings, or the mod, changing Dry Run mode, and resetting sessions clear warning state.
- Spam counting occurs after duplicate/outgoing-payment filtering and before bet-size validation. The feature warns players; it does not introduce a new bet-blocking rule.
- Advanced settings page for the warning toggle, threshold (2â€“20), window (1â€“60 seconds), and cooldown (5â€“600 seconds); warning state appears in `/autogamble status`.
- Custom warning text stored in `spamWarningMessage`, validated to 1â€“200 characters without control/format characters or line separators. Warning recipients must match 3â€“16 letters, numbers, or underscores.
- Config schema 5, migration of older settings, string type validation, and exact-integer validation for spam and prefix fields.
- Advanced-page help text moves down and is hidden at smaller window heights to accommodate the added button.

## Recovery verification

Both the baseline build and the supplied JAR were decompiled with Vineflower 1.11.1. Ten top-level Java files differed; changes were ported into the original source, retaining existing comments and tests. Comparing the rebuilt v1.1.0 against the supplied JAR produced identical decompiled Java for every class except an equivalent nested-versus-combined short-circuit condition in `PaymentSpamTracker`. This is a structural comparison, not a claim of byte-identical build output.

Validation: `gradlew.bat test build` passed, with **218 tests, 0 failures, 0 errors, and 0 skipped**. Archive entry lists match; metadata and language content match (language-file line endings differ).

The regression suite includes the original 209 tests (with config-version expectations updated) and 9 added spam-warning tests: thresholds, player separation, expiration, cooldown, retries, disable/reset, command validation, Dry Run, migration, and settings validation.

The original v1.1.0 source comments and tests were not included in the JAR and cannot be recovered. Minecraft UI and live server transactions were not exercised during this recovery.

## Preserved original artifact

`releases/autogamble-1.1.0.jar` is an unchanged copy of the supplied JAR.

SHA-256: `390b24ff34e0eb95d9eb503cbbf881a68dbbaa27dd6456ff2e1ac36d3f6e3f5f`

Build with Java 25 using `gradlew.bat test build` on Windows or `sh gradlew test build` elsewhere. Recovery tooling and downloaded Java tools are local-only and excluded from Git.
