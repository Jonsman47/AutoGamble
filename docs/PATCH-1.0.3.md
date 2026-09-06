# AutoGamble 1.0.3

This patch replaces the runtime empty-prefix cache/TAB fallback with a per-payment random alphabetic
prefix request. Default length is 1; the discovery constructor supports 2. Each first letter is chosen
uniformly from the unused letters in the current search. Valid server suggestions are randomized by
the existing selection RNG, with paid-history preference across prefixes. Empty or paid-only responses
retry after 200 ms; unanswered requests time out after 3 seconds. Maximum: 26 requests per selection.
No viable result skips the payment. Exhausting all letters with only previously paid results permits
the existing safe history reset and a random known result. No guessed player names are used.

excludeNumericOnlyNames defaults true and is editable under Auto Pay. Missing fields in schema-4
configs gain the default without losing other settings. Short names such as x7 are accepted for outgoing
payment targets as requested; incoming bet validation remains unchanged.

The exact normalized server error 'That player does not exist' attributes to the most recent live
advertising attempt within 3 seconds. It blacklists that name for 10 minutes in memory. Winner sends
and external/manual commands invalidate the attribution slot. AutoGamble's own command hook is guarded
so it does not invalidate itself. No server transaction ID is available, so delayed/reordered errors
cannot be attributed with certainty. Player-authored chat is rejected; session changes clear all state.

Tests: 165 passing (all 134 prior tests plus 31). Local UI smoke test passed, including the new Auto Pay
control layout, settings, confirmation and command routing. Gradle test/build passed. Release excludes
smoke harness. No real server connection or money movement was performed.

Minecraft 26.2, Java 25, Fabric Loader 0.19.3 and all other dependencies unchanged. Dry Run defaults ON.
Output: build/libs/autogamble-1.0.3.jar.

Changed: AutoPayManager, AutoPayEnvironment, MinecraftPaymentDispatcher, PaymentExecution,
AutoGambleClient, AutoGambleConfig, RuntimeSettingsChange, AutoGambleSettingsScreen, version and README.
Added: PrefixPlayerDiscovery, FailedTargetBlacklist, PrefixDiscoveryTest.
Old CommandSuggestionPlayerSource remains only for compatibility/tests; it is not used by runtime.

Fabric sendCommand invokes ALLOW_COMMAND internally, hence the own-dispatch guard:
https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-message-api-v1/src/client/java/net/fabricmc/fabric/mixin/client/message/ClientPacketListenerMixin.java
