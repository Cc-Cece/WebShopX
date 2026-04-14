# WebShopX Help {#top}

> This page is for players first, and server owners second.

## Quick Start (Players) {#player-quick-start}

1. Set web password in game: `/ws password <new_password>`
2. Sign in on the web page.
3. Check wallet, then use shop/market.
4. If an order is waiting for claim, run `/ws claim` in game.

## Market Basics {#market-basics}

- Direct listing: buy with a fixed price.
- Dynamic pricing: price changes based on demand.
- Auction listing: different auction algorithms are available.

### English Auction {#ENGLISH_AUCTION_V1}

Open ascending bid. Highest bid wins when time ends.

### Dutch Auction {#DUTCH_AUCTION_V1}

Price drops over time. First buyer closes the lot.

### Vickrey Auction {#VICKREY_AUCTION_V1}

Sealed bids. Highest bidder wins, pays second price.

### Candle Auction {#CANDLE_AUCTION_V1}

Open bid with randomized hidden extension window.

## Dynamic Pricing Algorithms {#dynamic-algorithms}

### Linear Demand {#LINEAR_DEMAND_V1}

Price rises steadily with demand.

### Diminishing Return {#DIMINISHING_RETURN_V1}

Growth impact slows down over time.

### Log Smooth {#LOG_SMOOTH_V1}

Smoother curve for frequent small trades.

### Exponential Defense {#EXPONENTIAL_DEFENSE_V1}

Large purchases raise price faster.

### Threshold Step {#THRESHOLD_STEP_V1}

Extra growth starts after threshold.

### Elasticity {#ELASTICITY_V1}

Elasticity controls sensitivity to demand.

### Panic Buying {#PANIC_BUYING_V1}

Accelerated growth after panic threshold.

## For Server Owners {#owner-quick-notes}

- Build command example: `./gradlew.bat shadowJar -Psnapshot=true`
- Test in staging before production.
- Backup database and plugin folder before upgrades.

## Custom Help Docs {#custom-help-docs}

This help system is Markdown-driven.

- `help.html` is the renderer and navigation shell.
- `docs/help.zh-CN.md` and `docs/help.en-US.md` are content sources.

Copy policy:

- If these files are missing, plugin copies defaults from jar.
- If files already exist, plugin keeps your custom version.
