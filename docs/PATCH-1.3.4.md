# AutoGamble 1.3.4 — minimum balance payment fix

Version 1.3.3 imposed a six-hour hard expiry on scanned balance records. Once all scanned records aged past that limit, a preflight check prevented even the legacy `/pay` suggestion search from starting, leaving the eligible queue empty and filtered Auto Pay unable to send anything. The cache crawler also skipped updating balances on pages already scanned, so repeating a scan could not renew those records.

Filtered payments now compare the configured minimum with the last known scanned balance using `BigDecimal`. Current `/pay` suggestions establish recipient availability, and the short-lived in-memory queue still expires after 30 seconds. Repeating a baltop scan updates stored balances on previously visited pages. Unknown balances remain ineligible. Valid comma-grouped amounts such as `100,000,000` are accepted alongside K/M/B/T suffixes.

Fake payment tests cover zero and positive minimums, below-threshold and unknown players, the twenty-first candidate, queue refill, timing, persisted case-insensitive cache lookup, and a legacy prefix suggestion reaching the final command generator. No test sends a real server command. Live DonutSMP validation is still needed.
