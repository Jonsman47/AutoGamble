# AutoGamble 1.0.5

First-time bonus defaults ON, +10 percentage points, clamped to 100% effective chance.
History: config/autogamble-payers.json (version 1), case-insensitive Locale.ROOT usernames, saved on
new accepted payer and confirmed reset. It is not part of transient session state. Rejected bets never
record payers. One roll occurs before first-time recording; both wins and losses consume status,
including real incoming bets processed in Dry Run. Parser tests do not touch history.
Gamble → First-Time Payer Bonus contains toggle, 0.1-point slider and confirmed persistent reset.
Status includes base odds, bonus state/value and known-payer count. Existing config gains missing defaults.
Corrupt history is backed up and recovers empty with logs; disk write errors are logged.

209 automated tests passed. The two existing base-probability tests now explicitly disable the optional
bonus to retain their original scope. Gradle test/build succeeded. No live DonutSMP transactions tested.
Minecraft 26.2, Java 25, Loader 0.19.3 and other dependencies unchanged. Old JARs are archived automatically.
Installable JAR: build/libs/autogamble-1.0.5.jar.
