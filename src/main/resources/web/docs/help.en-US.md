# WebShopX Basic User Manual {#top}

> 👋 Welcome to WebShopX! This guide is written specifically for players. You don't need to understand complex code—just follow the steps, and you'll easily become a master trader or snag your favorite items.

## Table of Contents Note {#toc-note}

- Click the directory on the left to quickly jump to the section you want to read.

---

## 🚀 3-Minute Quick Start {#player-quick-start}

### Step 1: Login {#player-login}

1. First, set your web login password in the game by typing: `/ws password <your_new_password>`
2. Open the web store and log in using your Minecraft username and the password you just set.
3. Login successful! It is recommended to check your "Wallet Balance" on the page first.

### Step 2: Understand the Two Currencies {#player-currency}

You might see two different types of money in the store (exact names depend on the server admin's settings):

- **GameCoin**: The money you normally use inside the server (usually based on the Vault plugin).
- **ShopCoin**: The exclusive currency for the web store. It is usually separate from GameCoin, unless the admin has enabled the "Currency Exchange" feature, allowing you to convert them at a set exchange rate.

### Step 3: Buy Items {#player-buy}

1. **Official Shop**: Supplied by the server. Prices are usually stable, and sometimes it supports official item buybacks.
2. **Player Market**: Items listed by other players. Prices and stock depend entirely on the market—first come, first served!
3. **Claiming**: After placing an order, check its status in the "Orders" tab. If it says "Pending Claim", go back to the game and type `/ws claim all` to receive all your purchased items at once.

### Step 4: Understand Order Statuses {#player-order-status}

- **⏳ Pending Issue (待发放)**: In a trading cooldown period, or the system is queuing the delivery.
- **🎁 Pending Claim (待领取)**: The item has arrived, and you need to manually claim it in the game using a command.
- **✅ Issued (已发放)**: The transaction is fully complete, and the item is in your inventory.
- **↩️ Refunded (已退款)**: The transaction failed or was canceled, and the money has been returned to your wallet.

---

## 🛒 How to Use the Player Market {#how-to-use-market}

### Buy It Now (DIRECT) {#direct-mode}

- **Rule**: See the price, confirm the amount, and pay directly to buy.
- **Feature**: The simplest type of trade, perfect for everyday, stable transactions.

### Dynamic Price (DIRECT + Dynamic) {#dynamic-mode}

- **Rule**: The price changes automatically based on **purchasing demand/heat**.
- **Feature**: When everyone is rushing to buy, the price might go up; if no one buys for a long time, the price might drop back down.
- *Note: This is not a system bug! It is a strategy set by the seller to simulate a real-world market.*

#### 📈 Common Dynamic Pricing Patterns (with Examples) {#dynamic-patterns}
When buying, the page will show the specific algorithm name. Understanding them can help you spot business opportunities:

* **Linear Demand** `LINEAR_DEMAND_V1`
  * **Behavior**: The more you buy, the more the price rises steadily. The easiest to understand.
  * **Common Items**: Basic minerals (Iron Ingots, Coal), common building materials (Oak Logs, Cobblestone).
* **Diminishing Return** `DIMINISHING_RETURN_V1`
  * **Behavior**: Rises quickly at first, but slows down significantly later. Buying in bulk won't become insanely expensive.
  * **Common Items**: Bulk building materials (Glass, Stone Bricks, Concrete, Dirt, Sand).
* **Log Smooth** `LOG_SMOOTH_V1`
  * **Behavior**: Price changes are very gentle for high-frequency, small purchases. No sudden price spikes.
  * **Common Items**: Daily consumables (Bread, Cooked Meat, Torches, Arrows).
* **Exponential Defense** `EXPONENTIAL_DEFENSE_V1`
  * **Behavior**: If you try to "hoard" a massive amount at once, the price will skyrocket! Mainly used to prevent wealthy players from monopolizing rare items.
  * **Common Items**: Top-tier strategic resources (Diamonds, Netherite Ingots, Beacons, Max Enchantment Books).
* **Threshold Step** `THRESHOLD_STEP_V1`
  * **Behavior**: The price barely changes when buying small amounts. But once purchases cross a specific threshold, the price jumps noticeably.
  * **Common Items**: Functional utility items (Shulker Shells, EXP Bottles, Ender Pearls, Spawn Eggs).
* **Elasticity** `ELASTICITY_V1`
  * **Behavior**: High elasticity = smooth price changes; Low elasticity = highly sensitive to purchases.
  * **Common Items**: Server special tokens, Emeralds, Gold Ingots (items easily affected by specific server mechanics).
* **Panic Buying** `PANIC_BUYING_V1`
  * **Behavior**: Once the number of buyers exceeds a critical point, the price accelerates upwards like a rocket.
  * **Common Items**: Limited holiday items, rare decorative heads, Enchanted Golden Apples.

### Auction Mode (AUCTION) {#auction-mode}

When selling top-tier gear, sellers usually choose auctions. The page will indicate the type of auction. Just remember how they work:

#### 🔨 English Auction {#ENGLISH_AUCTION_V1}
- Everyone bids openly. Whoever offers the most money temporarily takes the lead.
- When the countdown ends, the highest bidder wins the item.
- **Note**: If someone outbids you, your frozen money will automatically be refunded to your wallet.

#### 📉 Dutch Auction {#DUTCH_AUCTION_V1}
- The item starts at a sky-high price, and **the price gets cheaper as time passes**.
- Whoever thinks the current price is acceptable and is the first to click "Buyout" instantly wins!
- A test of nerves: The longer you wait, the cheaper it gets, but the easier it is for someone else to snatch it first.

#### 📦 Vickrey (Sealed-Bid) Auction {#VICKREY_AUCTION_V1}
- Blind bidding! You submit your "maximum acceptable price," and no one else can see how much you bid.
- After the deadline, the highest bidder wins.
- **Perk**: The winner doesn't pay their highest bid; they only pay the **second-highest bid amount**. (This encourages honest bidding).

#### 🕯️ Candle Auction {#CANDLE_AUCTION_V1}
- Open bidding like an English auction, but **the real end time is random**!
- Like a burning candle, no one knows exactly which second it will blow out. Don't try to "wait until the last second to bid," or you will likely miss it entirely.

---

## 📦 How to Manage My Listings (For Sellers) {#my-listings}

### Normal Listings {#listing-normal}
- You can change the price, edit notes, pause, resume, or take down the listing at any time.
- **Note**: Taken-down items will go through the return process. Please check your game inventory or type `/ws claim all` to check your claim queue.

### Supply Box Listings {#listing-supply-box}
- You can bind a chest in the game as a "Supply Container," and the web page will handle the pricing and sales.
- If the chest is broken or something goes wrong, the system will automatically protect your property and pause the sale of that item.

---

## ❓ FAQ {#faq}

### Q: I paid, but the item didn't go to my inventory immediately? {#faq-not-received}
It could be one of the following reasons:
1. The item went into the staging area. Please type `/ws claim all` in the game to claim it.
2. Your game inventory was full at the time! Clear some space and run the claim command again.
3. The server was slightly lagging, and the delivery queue is delayed by a few seconds. Please wait a moment.

### Q: After bidding in an auction, my balance went down. Was I charged by mistake? {#faq-bid-freeze}
To prevent fake bidding, most auction modes will **temporarily freeze** your bid amount. 
Don't panic! If someone outbids you, or if the item goes unsold, every single cent will be automatically refunded to your wallet.

### Q: Why does the "Dutch Auction" show the "Current Buyout Price" instead of the "Current Highest Bid"? {#faq-dutch-auction}
Because the rule of a Dutch auction is "price-drop clearance". People don't bid upwards; instead, it's a race to see who accepts the currently dropping price first. Clicking it means instant buyout.
