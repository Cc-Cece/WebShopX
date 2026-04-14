# WebShopX Manual {#top}

> Player-first documentation with owner-focused advanced notes.

## Quick Start {#quick-start}

1. Set your web password in-game: `/ws password <new_password>`
2. Sign in on the web page.
3. Check wallet balance and trading mode.
4. If order is waiting, run `/ws claim` in-game.

## Market Modes {#market-modes}

### Direct Listing (DIRECT) {#direct-mode}

#### Short Version (Players)

- Fixed price, direct purchase.
- Best for stable daily trading.

#### Advanced Notes (Owners)

- Settlement price is exactly listing price.
- Great baseline mode before enabling dynamic models.

### Dynamic Pricing (DIRECT + Dynamic) {#dynamic-mode}

#### Short Version (Players)

- High demand usually pushes price up.
- Cooling demand may smooth or lower prices.

#### Advanced Notes (Owners)

General shape:

$$
P_t = \operatorname{clamp}(f(D_t, S_t, \theta), P_{\min}, P_{\max})
$$

- \(D_t\): demand signal
- \(S_t\): supply state
- \(\theta\): algorithm parameters

Tune in order: floor/cap -> sensitivity -> smoothing.

### Auction (AUCTION) {#market-auction}

#### Short Version (Players)

- Different auction algorithms are available.
- Focus on how bidding and settlement work.

#### Advanced Notes (Owners)

- Separate gameplay parameters from risk-control parameters.
- Start with English auction, then enable sealed/candle modes.

## Dynamic Algorithms {#dynamic-algorithms}

### Linear Demand (LINEAR_DEMAND_V1) {#LINEAR_DEMAND_V1}

#### Short Version (Players)

- Price rises linearly with demand.

#### Advanced Notes (Owners)

$$
P_t = P_0 + kD_t
$$

Use floor and cap to bound extremes.

### Diminishing Return (DIMINISHING_RETURN_V1) {#DIMINISHING_RETURN_V1}

#### Short Version (Players)

- Early demand matters more; later impact slows down.

#### Advanced Notes (Owners)

$$
P_t = P_0 + a\frac{D_t}{1+bD_t}
$$

### Log Smooth (LOG_SMOOTH_V1) {#LOG_SMOOTH_V1}

#### Short Version (Players)

- Smoother price curve for frequent small trades.

#### Advanced Notes (Owners)

$$
P_t = P_0\left(1+\alpha\ln(1+D_t)\right)
$$

### Exponential Defense (EXPONENTIAL_DEFENSE_V1) {#EXPONENTIAL_DEFENSE_V1}

#### Short Version (Players)

- Large demand spikes increase price aggressively.

#### Advanced Notes (Owners)

$$
P_t = P_0 e^{\beta D_t}
$$

### Threshold Step (THRESHOLD_STEP_V1) {#THRESHOLD_STEP_V1}

#### Short Version (Players)

- Mild growth before threshold, stronger growth after.

#### Advanced Notes (Owners)

$$
P_t =
\begin{cases}
P_0 + k_1D_t, & D_t\le T \\
P_0 + k_1T + k_2(D_t-T), & D_t>T
\end{cases}
$$

### Elasticity (ELASTICITY_V1) {#ELASTICITY_V1}

#### Short Version (Players)

- Elasticity controls price sensitivity.

#### Advanced Notes (Owners)

$$
P_t = P_0\left(\frac{D_t+\varepsilon}{D_0+\varepsilon}\right)^{\eta}
$$

### Panic Buying (PANIC_BUYING_V1) {#PANIC_BUYING_V1}

#### Short Version (Players)

- Once panic threshold is crossed, growth accelerates.

#### Advanced Notes (Owners)

$$
P_t = P_0 + kD_t + \gamma\max(0,D_t-T)^2
$$

## Auction Algorithms {#auction-algorithms}

### English Auction (ENGLISH_AUCTION_V1) {#ENGLISH_AUCTION_V1}

#### Short Version (Players)

- Open ascending bids, highest bid wins.

#### Advanced Notes (Owners)

$$
P_{settle} = \max_i(b_i)
$$

### Dutch Auction (DUTCH_AUCTION_V1) {#DUTCH_AUCTION_V1}

#### Short Version (Players)

- Price falls over time, first buyer wins.

#### Advanced Notes (Owners)

$$
P(t)=\max(P_{floor}, P_{start}-r\Delta t)
$$

### Vickrey Auction (VICKREY_AUCTION_V1) {#VICKREY_AUCTION_V1}

#### Short Version (Players)

- Highest bidder wins, pays second-highest price.

#### Advanced Notes (Owners)

$$
winner = \arg\max_i b_i,\qquad P_{settle}=b_{(2)}
$$

### Candle Auction (CANDLE_AUCTION_V1) {#CANDLE_AUCTION_V1}

#### Short Version (Players)

- Public end time exists, real ending is randomized in a window.

#### Advanced Notes (Owners)

$$
T_{real-end}\sim\mathcal{U}(T_{public-end}, T_{public-end}+\delta)
$$

## Owner Operations {#owner-ops}

- Tune one variable at a time.
- Observe at least 3 to 7 days before next adjustment.
- Back up database and plugin folder before upgrades.
