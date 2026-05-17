# WebShopX Advanced User Manual {#top}

> **Document Nature**: Deep Manual ⚙️
> **Target Audience**: Server Administrators, Advanced Players
> **Key Content**: Dynamic Pricing Algorithms, Auction Mechanism Algorithms

---

## 🏗️ Chapter 1: Core Architecture & Design Philosophy {#architecture}

Dynamic pricing and auction algorithms transform WebShopX from a traditional "static shop" into an adaptive economic engine driven by a **Demand-Driven Feedback** mechanism. The core objective of the system is to automatically regulate commodity prices through algorithms, using mathematical models to hedge against server inflation.

### 1.1 Core Protection Mechanisms
* **🛡️ Clamping Function**: All dynamic prices, after complex formula calculations, are ultimately converged within the absolute safety range set by the administrator:
  $$P_{\text{final}} = \max(P_{\min}, \min(P_{\text{calc}}, P_{\max}))$$
  This means that no matter how the algorithm fluctuates, the price will never breach $P_{\max}$ (Price Ceiling) or $P_{\min}$ (Floor Price).
* **📜 Transaction Traceability**: The underlying `MarketService` is deeply bound with the persistence layer. Every order and every price evolution has detailed log records, supporting economic post-mortems in the event of a market run.
* **🔒 Optimistic Concurrency Control**: Utilizing database optimistic locking based on the `version` field, the system strictly prevents "overselling" and "read-write competition" loopholes in high-frequency trading scenarios (such as server-wide flash sales).

---

## 🧪 Chapter 2: Dynamic Pricing Algorithms (7 Cornerstone Models) {#dynamic-algorithms}

The system monitors **Demand Heat ($D_t$)** and utilizes the following mathematical models to calculate transaction prices in real-time. Each algorithm corresponds to a different economic regulation purpose.

### 📈 1. Linear Demand (LINEAR_DEMAND_V1)
The most classic and intuitive supply-demand model. The more you buy, the more the price rises steadily.
* **Math Formula**: $P_t = P_0 + k \cdot D_t$
* **Parameter Analysis**: $k$ is the price slope. If $k=0.5$, the price increases by 0.5 for every 1 point of heat added.
* **Economic Application**: Suitable for **Basic Minerals (Iron Ingots, Coal)** and other hard currencies with stable output and high consumption. It effectively sets the base price benchmark for the server.

### 📉 2. Diminishing Return (DIMINISHING_RETURN_V1)
Price increases are significant initially, but as heat continues to climb, the rate of increase is strongly suppressed, eventually approaching an upper limit.
* **Math Formula**: $P_t = P_0 + a \cdot \frac{D_t}{1 + bD_t}$
* **Parameter Analysis**: The maximum premium is $a/b$. If $a=10, b=0.1$, the price will at most be 100 units higher than the original price, regardless of quantity.
* **Economic Application**: Suitable for **Bulk Building Blocks (Dirt, Stone Bricks, Concrete)**. It protects builder players from going bankrupt when purchasing thousands of blocks.

### 🌊 3. Logarithmic Smoothing (LOG_SMOOTH_V1)
A model extremely desensitized to numerical changes, featuring a very smooth curve.
* **Math Formula**: $P_t = P_0 \cdot (1 + \alpha \ln(1 + D_t))$
* **Parameter Analysis**: $\alpha$ controls the smoothness. As heat rises from 100 to 1000, the price might increase by less than double.
* **Economic Application**: Suitable for **Daily Consumables (Torches, Bread, Arrows)**. Players will barely notice price hikes during small, fragmented purchases, providing an excellent user experience.

### 🚀 4. Exponential Defense (EXPONENTIAL_DEFENSE_V1)
A powerful anti-monopoly defense tower. Small increases in heat lead to geometric, "nuclear-level" price spikes.
* **Math Formula**: $P_t = P_0 \cdot e^{\beta D_t}$
* **Parameter Analysis**: $\beta$ is the exponential multiplier, typically set between 0.01 and 0.05.
* **Economic Application**: Suitable for **Strategic Materials (Netherite Ingots, Beacons, Top-tier Enchanted Books)**. It allows regular players to buy one or two, but if a "whale" attempts to buy 50 at once to clear stock, the astronomical price will instantly drain their wallet. **Strict configuration of $P_{\max}$ is strongly recommended.**

### 🚧 5. Threshold Step (THRESHOLD_STEP_V1)
Everything stays calm until a red line is touched, after which the system strikes back hard.
* **Math Formula**:
  * When $D_t \le T$: $P_t = P_0 + k_1 D_t$
  * When $D_t > T$: $P_t = P_0 + k_1 T + k_2(D_t - T)$ (where $k_2 \gg k_1$)
* **Parameter Analysis**: $T$ is the safety threshold.
* **Economic Application**: Suitable for **Functional Utility Items (Shulker Shells, EXP Bottles, Spawn Eggs)**. It precisely distinguishes between "regular players buying for use" and "studios hoarding for resale."

### ⚖️ 6. Elasticity Model (ELASTICITY_V1)
Introduces the concept of "Price Elasticity" from economics.
* **Math Formula**: $P_t = P_0 \cdot (\frac{D_t + \varepsilon}{D_0 + \varepsilon})^{\eta}$
* **Parameter Analysis**: $\eta$ (Eta) is the elasticity index. $\eta < 1$ represents a sluggish market (resistant to falls/rises), while $\eta > 1$ represents a highly sensitive market (drastic spikes/falls).
* **Economic Application**: Suitable for **Special Tokens and Emeralds**. Admins can directly manipulate the market's nervous sensitivity by adjusting $\eta$.

### 🔥 7. Panic Buying (PANIC_BUYING_V1)
Simulates "Herd Mentality" and bank runs in the real world.
* **Math Formula**: $P_t = P_0 + kD_t + \gamma \cdot \max(0, D_t - T)^2$
* **Parameter Analysis**: Introduces a quadratic term $\gamma$. After breaching threshold $T$, the price enters a parabolic acceleration path.
* **Economic Application**: Suitable for **Limited Edition Heads and Event Mystery Boxes**. It triggers player panic-buying through extreme price surges.

---

## 🔨 Chapter 3: Auction Mechanism Algorithms (4 Major Modes) {#auction-modes}

### 📢 1. English Open Auction
* **Mechanism**: Classic "Highest Bidder Wins." Each valid bid becomes the new **Benchmark Price**.
* **Anti-Snipe**: The system features a soft delay mechanism. If a bid is placed within the last $N$ seconds, the countdown automatically extends by $M$ seconds, effectively eliminating "last-second sniping" tactics.
* **Asset Freezing**: Funds are **Hard Locked** in the player's wallet the moment a bid is placed. If outbid, the `AsyncRefundTask` returns the funds within milliseconds.

### 📉 2. Dutch Declining Auction
* **Mechanism**: Starts at an absurdly high price and drops in steps over time (e.g., a 5% price drop every hour).
* **Psychological Game**: All players watch an item simultaneously; the first to click "Buyout" wins instantly. The longer you wait, the cheaper it gets, but the easier it is to be intercepted.
* **Applications**: Official server clearance sales, high-value land auctions.

### 📦 3. Vickrey (Sealed Second-Price) Auction
* **Mechanism**: Sealed-bid mode. All bids are encrypted and hidden until the auction closes. Upon closing, the **highest bidder wins, but they only pay the "Second Highest Bid Amount."**
* **Economic Principle**: A Nobel Prize-winning design. It eliminates the "Winner's Curse" in game theory and mathematically proves that **"bidding one's true maximum value" is the unique Dominant Strategy**. No guessing required—just return to the true value of the item.

### 🕯️ 4. Candle Random Auction
* **Mechanism**: Operates similarly to an English auction, but **the true end point is a hidden random number**.
* **Feature**: The UI might show an end time of 8:00 PM, but the system may quietly "blow out the candle" and settle at any millisecond between 7:55 PM and 8:00 PM. Players are forced to provide competitive bids early rather than waiting until the end.

---

## 🔧 Chapter 4: Admin In-depth Tuning Guide {#admin-tuning}

### 4.1 Dual-Track Drive of Price Heat
The dynamic pricing engine is influenced not only by purchases but also by time:
1. **Positive Feedback (Purchase/Event Driven)**: Every time a player orders, the system injects heat (Demand) into the commodity pool based on the quantity purchased.
2. **Negative Feedback (Time Driven/Decay)**: An internal Cron task runs at fixed intervals (e.g., every 6 hours) to execute decay: $D_{\text{new}} = D_{\text{old}} \cdot (1 - \text{DecayRate})$.

### 4.2 Tuning Safety Redlines (Safety Protocols)
* **Canary Testing**: Do not change the entire server's currency items to dynamic pricing at once. Start with "marginal items" like Rotten Flesh or Bones to create a test field. Observe player behavior and curves for 3-5 days before full rollout.
* **Hot-Update Protection**: You can modify the `.md` documentation files in the `web/docs/` directory at any time. The plugin triggers a protection mechanism upon detecting file changes, and **will not** overwrite your modifications with default documents during a restart.

---

## 🐛 Chapter 5: Troubleshooting & Ticket Support {#troubleshooting}

**Q1: Why does the web UI show one price, but I get a "Price Changed" error when clicking buy?**
A: This is normal. In high-frequency dynamic markets, there is a time gap between you viewing the page and initiating the request. If someone else buys first, the heat update makes the latest price higher than the cached price you saw. This is also the `version` optimistic lock providing protection.

**Q2: Players report that money is stuck in "Frozen" status and wasn't returned after being outbid?**
A: Most likely, the server experienced a transient TPS drop or Out of Memory (OOM) error, causing the `AsyncRefundTask` to be suspended.
**Solution**: Admins should check logs to locate the error, then manually invoke admin commands or restart the plugin to sync the state.

---

### 📩 Core Technical Support & Bug Tracking

WebShopX is continuously being refined. If you encounter any bugs during deployment or wish to collaborate on the code, please contact us!

👉 **Please submit Issues to the official GitHub repository:**
[WebShopX-Issues (Click to enter Feedback Center)](https://github.com/Prism-Committee/WebShopX-Issues)
