# AutoGamble 1.0.2 verification

Minecraft 26.2, Java 25, Loader 0.19.3, Fabric API 0.159.0+26.2, Loom 1.17.20 remain unchanged.
Config schema stays 4; user settings, built-in parser flag and custom patterns are preserved.
Dry Run still defaults ON.

## Changes

- GAME (system/overlay) and CHAT (profileless server messages) feed one receipt pipeline.
  Signed/player-profile chat is observed for diagnostics but rejected as a bet.
- The exact live text `JonsmanV6or7 paid you $ 19.8k` parses to 19800; at 2x the queued payout is 39600.
- Trusted built-in parsing no longer depends on a 5ms wall-clock budget that could be exceeded by a frame pause.
  Untrusted configurable patterns retain bounded matching. Whitespace/style normalization is preserved.
- Vanilla ClientSuggestionProvider.customSuggestion requests `/pay ` autocomplete, never command execution.
  Cache and request interval are 20 seconds. No request begins during an open screen. Vanilla cancellation
  by a user's subsequent chat completion safely leaves TAB fallback. Session tokens reject stale responses.
- Suggestions are validated, deduplicated, exclude self, and selected randomly with existing unpaid history.
- Advertising checks current candidates; accepted winners no longer require TAB membership.
  Username/self/connection/screen/feature/Dry Run/one-command-per-tick guards remain.
- Status exposes source/count, block state, last parsed receipt/result, queue and timing.
  `/autogamble debug on` logs up to 100 received messages with channels and displays parsed outcomes;
  `/autogamble debug off` turns this off. Debug is off by default and not persisted.

## Verification and limitations

134 automated tests passed (110 retained + 24 added). Gradle test and build succeeded.
Local Minecraft UI smoke test passed on Loader 0.19.3: settings, controls, confirmation,
command interception, invalid-input handling and clean shutdown. Temporary harness is excluded from release.
Tests exercise manager-to-PaymentExecution dry/real callbacks, one-roll deduplication, exact command
strings, source validation/cache expiry/session cancellation and unpaid/random selection.
No DonutSMP connection or real payment occurred during development. Server-wide autocomplete exposure,
actual receive channel and real server acceptance are not established by local tests.

The old GAME-only hook omitted profileless CHAT. Fabric's 26.2 ChatListenerMixin explicitly routes
handleDisguisedChatMessage through CHAT with null signed message and null profile:
https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-message-api-v1/src/client/java/net/fabricmc/fabric/mixin/client/message/ChatListenerMixin.java
This verifies an omitted path, not the specific live cause. The old TAB-only dispatch guard also blocked
valid recipients missing from TAB; Dry Run ON intentionally prevented all real commands. New diagnostics
are needed to determine which conditions applied in the user's live session. Do not interpret player-authored
CHAT as proof of a transaction. Outgoing/error message formats and server acknowledgments remain unverified.

## Files

Added CommandSuggestionPlayerSource and LiveFlowTest. Changed AutoGambleClient, ReceivedMessage,
RegexPaymentParser, MinecraftPaymentDispatcher, AutoPayManager, GambleManager,
AutoGambleSettingsScreen, Gradle version, metadata and README.
Installable artifact: build/libs/autogamble-1.0.2.jar.
