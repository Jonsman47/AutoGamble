# AutoGamble 1.3.2 — legacy payments restored

Auto Pay again discovers and chooses recipients using the exact 1.2.4 random `/pay` prefix-suggestion path. The newer baltop recipient selector and second autocomplete verification are retained in source but hard-disabled for normal gameplay. Saved 100% Baltop weights cannot suppress legacy payments. The targeting settings remain visible and explain that payment targeting is Coming Soon; the independent scanner and Scanned Baltop browser remain available.

The Auto Pay settings now include **Minimum Payment Balance**, default **0**. Zero bypasses the filter entirely and does not read the baltop database. When positive, this optional filter checks up to 30 already-discovered recipients in random order against locally known per-player balances from the scanned cache. Unknown and below-threshold balances are skipped; if none qualify, that payment cycle ends and the next normal delay is scheduled. This filter applies to advertising payments, not gambling payouts or other payment sources.

Existing settings and scanned data survive migration. The 1.3.1 published JAR and matching sources JAR are archived before release. A controlled live test should confirm ordinary payments with threshold 0 and optional filtering with known cached balances.
