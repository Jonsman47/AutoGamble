# AutoGamble

A client-side Fabric mod for DonutSMP with automatic payments, configurable gambling, and in-game settings.

## Install

Requires **Minecraft 26.2**, **Java 25**, **Fabric Loader 0.19.3**, and **Fabric API 0.159.0+26.2**.

1. Download **autogamble-1.0.5.jar** from [Releases](https://github.com/Jonsman47/AutoGamble/releases/latest).
2. Put it and Fabric API in your Minecraft `mods` folder. Remove older AutoGamble JARs.
3. Launch Minecraft and press **F9** to configure it.

**Dry Run is ON by default:** the mod simulates payments without sending money. Auto Pay and Gambling start OFF. Test with Dry Run before enabling real payments.

## Features

- **Auto Pay:** searches `/pay` autocomplete using random 1–3 letter prefixes, then randomly chooses a valid player. Configurable amount and delay, unpaid-player preference, numeric-name filter, and temporary failed-target blacklist.
- **Gambling:** recognizes incoming notices such as `Bob paid you $ 19.8k`. Supports K/M/B/T amounts, configurable win chance, bet limits, payout multiplier, and queued payouts.
- **First-time bonus:** ON by default, adding **10 percentage points** to a player's first accepted bet, capped at 100%. Payer history persists across restarts. Accepted Dry Run bets also consume the bonus.

## Controls

| Key or command | Action |
| --- | --- |
| **F8** | Toggle AutoGamble |
| **F9** or `/settings Gamble` | Open settings |
| `/autogamble status` | Show current status |
| `/autogamble debug on` / `off` | Toggle receive diagnostics |

Rebind keys in Minecraft's **Options → Controls → Key Binds**. Settings apply with **Save & Done**.

Config: `config/autogamble.json`  
Payer history: `config/autogamble-payers.json`

## Development

With Java 25, run `gradlew.bat test` then `gradlew.bat build` (Linux/macOS: `sh gradlew`).

Version **1.0.5**: **209 tests passed**. Local Minecraft UI checks passed; live DonutSMP transactions were not independently tested during development. See [patch notes](docs/PATCH-1.0.5.md) for details.
