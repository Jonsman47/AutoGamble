# AutoGamble 1.2.1

AutoGamble 1.2.1 adds local command help, payment sound alerts, and in-game analytics while retaining the Minecraft 26.2, Java 25, Fabric Loader 0.19.3 environment and all 1.2.0 automation behavior.

## Local command help

Use `/help gamble` or `/help gamble <page>`. AutoGamble intercepts these exact commands locally, displays at most 15 registered commands per page, and never sends them to the server. Unrelated `/help` commands continue normally.

The command list comes from `HelpRegistry`. Every future user-facing command must be registered there with its syntax and one-line description.

## Payment sound alerts

Payment sounds default to ON. The default tiers are 20M, 50M, 100M, 250M, and 500M, each with a distinct built-in Minecraft sound. The highest enabled matching tier plays once per validated payment. Tier thresholds, enabled states, sound IDs, volume, pitch, and the global 250 ms minimum spacing are editable under **General → Payment Sound Alerts**.

Alerts run after sender validation, receipt deduplication, and outgoing-payment suppression. They can still alert for a real incoming payment outside configured gambling bet limits. Malformed, duplicate, and outgoing-echo messages do not alert.

## Analytics

Open **General → Analytics Dashboard** for:

- Session totals and rates, gambling result, advertising cost, and tracked net profit.
- Theoretical EV using total-return payout semantics: `bet × (1 − win chance × payout multiplier)`. First-time bonus EV is shown separately.
- Persistent per-customer paid-by, gamble-paid-back, net, bet, win/loss, and latest-payment data.
- Session and lifetime Auto-Pay advertising spend, unique advertised and converted players, attributed revenue/profit, conversion rate, and ROI.

Current-session counters reset on disconnect or server/world switch. Durable customer and lifetime ROI data lives at `config/autogamble/data/analytics.json`, uses case-insensitive player identity, writes off the client thread, and is backed up before corrupt data is reset.

An actual advertising Auto-Pay dispatch creates the latest touch. A later verified payment from the same player within the conversion window converts that player, and subsequent payments remain attributed for the configured duration. Both windows default to 1,800 seconds. Dry Run outgoing payments never create spend, touches, or paid-back totals.

## Safety and compatibility

The config schema is version 7. Migration retains all prior gambling, payer, spam, parser, reporting, follow, balance-rule, and Auto-Pay values. Dry Run stays ON by default; Auto Pay, Gambling, Good Customer Follow, and Balance Payment Rules retain their safe defaults. Balance-rule triggering still requires a reliable known balance and never infers one from transaction arithmetic.
