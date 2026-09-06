# AutoGamble 1.0.1 patch

This report and the root README supersede earlier staged design/verification notes.

- Minecraft remains 26.2; Java remains 25.
- Fabric Loader is compiled against 0.19.3 and the mod requires `fabricloader >=0.19.3`.
- Fabric API 0.159.0+26.2 and Loom 1.17.20 are unchanged. Fabric API declares Loader >=0.18.4.
- Mod/display version is 1.0.1. Config schema is 4.
- The confirmed incoming message `<player> paid you $ <amount>` is built in and enabled by default.
  The original Java username validation of 3–16 characters is preserved throughout dispatch.
- Decimal-safe, case-insensitive K/M/B/T suffix parsing expands before every monetary comparison.
  Parser-test results display expanded amounts. Incoming values are bounded at one trillion; existing
  bet and payout configuration bounds are otherwise unchanged.
- `donutSmpIncomingEnabled` is separate from the user's custom pattern list. Migration preserves the
  list without adding duplicate entries. Exact custom copies override the built-in, even when disabled.
- Dry Run still defaults ON, and the final shared command guard is unchanged.

The new test class adds 30 tests covering all supplied plain/K/M examples, B/T and decimal suffixes,
commas, malformed input, normalized color/whitespace, bet bounds, canonical outgoing comparisons,
deduplication, exactly one roll, expanded payouts, dry-run execution, pattern migration and Loader metadata.
All 80 prior tests are retained; schema assertions and the old rejection assertions for now-valid suffixes
and larger incoming amounts were updated to match the requested behavior.

The local development client launched successfully with Loader 0.19.3 and initialized one incoming
pattern with dry run=true. The UI smoke test passed for screens, confirmation, command routing and
validation. The smoke driver is temporary and excluded from release artifacts.
No DonutSMP connection or real payment is used by the tests.

The user's authentic incoming examples establish the incoming wording, including the separate dollar
sign. Outgoing confirmations, error formats, real transaction acceptance and server behavior still need
live verification. Start with Dry Run. B/T support is implemented as requested rather than claimed to
have been observed in the provided samples.
