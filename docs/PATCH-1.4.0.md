# AutoGamble 1.4.0

This stable release brings together the changes made since 1.2.4 for Minecraft 26.2. Existing saved settings are retained; the defaults below apply to new or previously uninitialized settings.

### Auto Pay

- Auto Pay still chooses recipients from the working random `/pay` autocomplete flow, not the unfinished Baltop payout mode.
- Minimum Payment Balance filters suggested recipients using known, locally scanned balances. A short-lived queue gathers eligible recipients ahead of payment time so filtering does not add the payment delay. The 1.3.4 fix removed the expired-balance dead end and refreshes records when pages are rescanned.
- New defaults: $1 per payment, 0.2–1.8 seconds between payments, prefer unpaid players, skip numeric-only names, and a 25M minimum balance. Set the minimum to 0 to bypass balance filtering. With a positive minimum, unknown balances are skipped, so scan Baltop first to populate balance data.

### Gambling

- New profiles use a 42.5% base win chance, a 15-percentage-point first-time player bonus, a $100M maximum bet, and a 250–1000 ms winner-payout delay. Older saved values remain unchanged.
- Incoming-payment parsing, first-time history, winner queueing, dry run, and payout behavior remain available.

### Interface

- Settings are grouped under Automation, Gambling, Data & Advanced, and Customization, with the existing controls retained in subpages.
- Customization adds editable quick commands that run only when clicked, compact spacing, optional visibility for less-used sections, an RGB accent with presets, and reset controls.
- The mod-list metadata now names Jonsman as author and includes an AutoGamble icon.

### Compatibility

- Minecraft 26.2, Fabric Loader 0.19.3+, Fabric API 0.159.0+26.2, and Java 25.
- Existing 1.2.4-era and 1.3.x configuration values migrate without being replaced by the new defaults.

### Experimental / Coming Soon

- The explicit in-game Baltop scanner and searchable local player database are retained. The earlier third-party website targeting experiment was retired from active code in 1.3.0 and remains in Git history; no site scraper or DonutSMP API key is needed.
- Baltop recipient weighting and payout options remain visible as Coming Soon and cannot alter live Auto Pay recipient selection. Starting a scan pauses advertising until the scan stops.

### Fixes and verification

- Changes since 1.2.4 include Baltop scan progress persistence and resume, leaderboard display/rank corrections, targeting diagnostics, restoration of legacy payment selection after a broken experimental payout path, bounded balance eligibility queuing, and regression tests using fake payments rather than server commands.
- A live DonutSMP check is still needed for the new menu layout, mod-list icon rendering, and real payment timing. Automated tests do not send real payments.
