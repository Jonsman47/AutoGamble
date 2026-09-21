# AutoGamble

A client-side Fabric mod for DonutSMP with automatic payments, configurable gambling, and in-game settings.

## Install

Requires **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.3**, and **Fabric API 0.159.0+26.2**.

1. Download the current JAR from the [GitHub releases](https://github.com/Jonsman47/AutoGamble/releases), or build it locally.
2. Put it and Fabric API in your Minecraft `mods` folder. Remove older AutoGamble JARs.
3. Launch Minecraft and press **F9** to configure it.

**Dry Run is ON by default:** the mod simulates payments without sending money. Auto Pay and Gambling start OFF. Test with Dry Run before enabling real payments.

## Features

- **Auto Pay:** weighted Smart Random Online and locally scanned Baltop targeting (50/50 by default). Every selected candidate receives a fresh exact-name `/pay` autocomplete verification before payment.
- **Scanned Baltop:** explicitly start a tick-driven scan of the in-game `/baltop` inventory. The scanner locates Next Page by item name, records players, balances, rank and source page, and persists progress across restarts. Open **Advertising Targeting → Scanned Baltop** to browse, search, filter, sort, pause, resume, or confirm a reset. The scan temporarily pauses advertising and never needs a website or API key. Its item-text parser should be checked on the live server before relying on the database.
- **Gambling:** recognizes incoming notices such as `Bob paid you $ 19.8k`. Supports K/M/B/T amounts, configurable win chance, bet limits, payout multiplier, and queued payouts.
- **First-time bonus:** ON by default, adding **10 percentage points** to a player's first accepted bet, capped at 100%. Payer history persists across restarts. Accepted Dry Run bets also consume the bonus.

- **Payment-spam warnings:** automatically sends `/msg` after 3 payments from the same player within 10 seconds, with a 60-second cooldown by default. Configure the toggle, threshold, window, and cooldown under **Advanced → Spam Payment Warning**. The message can be customized using `spamWarningMessage` in the config file. Dry Run logs warnings without sending them.

- **Good Customer Follow:** OFF by default. Once a customer has paid a cumulative **5M** (configurable), dispatches `/follow` once and persists their identity. Dry Run only logs; it never adds a simulated follow to the permanent list.
- **Payment reports:** automatically writes received totals, paid totals and filtered recent transactions to UTF-8 text files. Reports and retained history persist across restarts. Filters never delete the underlying retained transactions.
- **Balance Payment Rules:** OFF by default, with no preset rules. Includes multiple rule editors, threshold re-arming, cooldowns and known-balance checks. **Live triggering is unavailable until a verified DonutSMP balance source is connected. Current balance stays UNKNOWN; no value is inferred from payment arithmetic.**
- **Payment sound alerts:** ON by default. Five independently configurable tiers start at 20M, 50M, 100M, 250M and 500M. Only the highest matching tier plays for a validated incoming payment.
- **Analytics:** four in-game pages show current-session results, theoretical EV, persistent customer profitability, and session/lifetime Auto-Pay ROI. Auto-Pay attribution uses configurable last-touch conversion and attribution windows.
- **Local command help:** `/help gamble` and `/help gamble <page>` show the complete AutoGamble command registry without sending the command to the server.

Open **General** for automation, sound, and analytics pages. See [the v1.2.1 guide](docs/PATCH-1.2.1.md) for the new settings and data path.

## Controls

| Key or command | Action |
| --- | --- |
| **F8** | Toggle AutoGamble |
| **F9** or `/settings Gamble` | Open settings |
| `/autogamble status` | Show current status, customer follow counts and balance rule status |
| `/autogamble reports refresh` | Schedule a refresh of enabled report files |
| `/autogamble reports status` | Show report path, toggles and stored data counts |
| `/autogamble debug on` / `off` | Toggle receive diagnostics |
| `/help gamble [page]` | Show paginated AutoGamble command help locally |

Rebind keys in Minecraft's **Options → Controls → Key Binds**. Settings apply with **Save & Done**.

Config: `config/autogamble.json`  
Payer history: `config/autogamble-payers.json`

## Development

With Java 25, run `gradlew.bat test` then `gradlew.bat build` (Linux/macOS: `sh gradlew`).

Version **1.3.0** replaces the 1.2.5 website experiment with an in-game Baltop scanner and local database. Earlier release JARs and historical source remain preserved. New builds go to `build/libs/`. See [v1.3.0 notes](docs/PATCH-1.3.0.md).

Before publishing each new version, archive the outgoing release's exact published JAR and matching sources JAR under `releases/archive/<version>/`. See the [release checklist](docs/RELEASING.md).
