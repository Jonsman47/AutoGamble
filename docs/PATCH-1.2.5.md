# AutoGamble 1.2.5

AutoGamble 1.2.5 upgrades advertising target selection while preserving the existing `/pay` autocomplete workflow and gambling behavior.

## Highlights

- Configurable weighted targeting defaults: 50% Smart Random Online, 30% Money Leaderboard, 15% Economy Active, and 5% Experimental.
- Strict in-game percentage validation with a live total, recommended-default reset, and disabled saving until the total is exactly 100%.
- Public leaderboard providers for `donutsmpstats.net` and `donutstats.org`; no DonutSMP API key is required.
- Asynchronous refresh, persistent local cache, provider status, manual refresh, rate limiting, timeouts, and failure backoff.
- Configurable wealth filters with optional maximum, quick presets, and K/M/B/T input such as `500M`, `1.5B`, and `2T`.
- Exact online verification through fresh `/pay <username>` command suggestions before every website-derived payment.
- Ten-minute recent-target cooldown, local-player exclusion, deduplication, bounded retries, and enabled-method fallback.
- Persistent per-method attempts, valid candidates, offline rejections, payments, unique targets, blocked repeats, conversions, revenue, and profit-per-1,000 analytics.

## Compatibility

- Minecraft 26.2
- Fabric Loader 0.19.3 or newer
- Fabric API 0.159.0+26.2
- Java 25

Dry Run remains enabled by default. Auto Pay and Gambling remain disabled by default.
