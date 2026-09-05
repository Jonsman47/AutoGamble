# AutoGamble 1.0.0 — final verification

Minecraft 26.2 and Java 25 remain unchanged. Fabric Loader 0.19.5, Fabric API 0.159.0+26.2,
Loom 1.17.20 and Gradle 9.5.1 remain unchanged. Config schema is now 3; prior settings migrate
with dryRunMode=true when that field is missing.

## Added classes

- UI: AutoGambleSettingsScreen, AdvancedParserScreen, WinChanceSlider, SettingsContext,
  SettingsCommandRouter, PatternConfigService, ParserTestService.
- Configuration: SettingsDraft, SettingsValidation, RuntimeSettingsChange.
- Payment safety: PaymentExecution.
- Tests: SettingsTest (20 tests).

## Modified classes

AutoGambleClient, AutoGambleConfig, ConfigManager, MinecraftPaymentDispatcher, GambleManager,
AutoPayManager and WinnerPayoutProcessor. FoundationTest and GambleTest migration expectations
were updated for schema 3. The other prior tests are retained. Build metadata, README and resources
identify the final release as 1.0.0.

## Local Minecraft smoke test

A temporary development-only client initializer launched Minecraft 26.2 without a server connection.
It constructed and initialized all six settings pages, navigated to the Advanced parser tester,
opened Minecraft's Key Binds screen, opened the real-payment confirmation and cancelled it.
Screenshots were inspected for layout/overlap issues at the default development window size.

The test invoked Fabric's registered ALLOW_COMMAND event with `settings GAMBLE`, verified it returned
false, and verified the next client tick opened AutoGambleSettingsScreen. It verified an unrelated
`settings SomethingElse` returned true. It also entered invalid numeric text into the real EditBox
and verified the Save button became inactive.

Result: `AUTOGAMBLE_UI_SMOKE_PASSED: screens, confirmation, command routing and validation`.
The smoke driver and its temporary Gradle init script are outside the project and are excluded from
the clean release build and source ZIP. The smoke run did not enable real payments or join a server.

## Release checks

The final release is rebuilt cleanly after the smoke run, with the ordinary test/build commands.
The full suite contains 80 tests. The final JAR metadata and contents are checked to ensure there is
only the production client entrypoint and no smoke-test class.

## Important behavior

- `/settings Gamble` uses ClientSendMessageEvents.ALLOW_COMMAND, exact command interception and a
  deferred screen request. It is cancelled locally and is not sent to the server. No server settings
  command root is claimed. `/autogamble settings` and status use ClientCommandRegistrationCallback.
- Screen sections: General, Auto Pay, Gamble, Timing, Advanced, Keys. Save commits the draft; Cancel
  discards it. Standard Minecraft Key Binds handles F8/F9 rebinding and persists those independently.
- PaymentExecution bypasses the real command callback in dry run for both advertising and winners.
  Dry-to-live GUI commits require confirmation. Mode changes cancel pending work and reset deadlines.
- Winner jobs are considered before advertising and share a one-attempt-per-tick gate. Disabled
  gambling/master state or session changes clear pending winners with a log message.
- The parser tester uses only RegexPaymentParser and returns a match/result string; it cannot invoke
  the gamble engine or command dispatcher. JSON pattern import changes only the unsaved pattern array.
- No incoming patterns are enabled by default. Authentic DonutSMP incoming, outgoing and error samples
  are still needed. No real payment acceptance, live player behavior or production server transaction
  format was tested. No confirmed DonutSMP compatibility claim is made.

The independent logic tests and local UI smoke test do not establish real-server acceptance, actual
chat-delivery provenance, full reconnect behavior on DonutSMP, or every possible GUI-scale layout.
