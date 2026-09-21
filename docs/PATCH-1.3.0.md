# AutoGamble 1.3.0 — in-game Baltop scan

This version retains the stable payment, gambling, settings and analytics behavior while retiring active third-party leaderboard providers from 1.2.5. That experiment remains in Git history. No leaderboard website, DonutSMP API key or `/baltop <page>` argument is used.

Open F9 → Advertising Targeting → Scanned Baltop. Start scanning while connected to DonutSMP and leave the inventory open. The client opens `/baltop`, recognizes `Most Money (Page N)`, reads item names and lore, detects the named Next Page item regardless of slot, and advances after a safe delay. Closing the inventory pauses with progress preserved. Resume starts at page 1 and clicks through already scanned pages; reset requires confirmation and clears only the scan database. Debug mode logs item names, lore and components to help diagnose server-side layout changes.

The local database is `config/autogamble/data/baltop-cache.json`; it stores canonical player names, balances, ranks, source pages, timestamps and scan progress. The browser offers search, range filters (K/M/B/T), rank/balance/page sorting and scrolling. The targeting editor defaults to 50% Smart Random and 50% Baltop, with an inclusive 500M minimum and no maximum. Both methods verify an exact username in fresh `/pay` suggestions before a payment. An empty baltop pool falls back to Smart Random. The scan is explicit maintenance: advertising pauses while it runs.

Java 25 and the Minecraft 26.2 Fabric dependencies are unchanged. Run `gradlew.bat clean test build` and inspect `build/libs/autogamble-1.3.0.jar`. Unit tests mock displayed inventory items; a live DonutSMP scan, page transition, and real autocomplete behavior still require in-game validation. Keep Dry Run enabled for that first check.
