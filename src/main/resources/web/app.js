const state = {
  token: null,
};

const logBox = document.getElementById("logBox");

function log(message) {
  const now = new Date().toISOString();
  logBox.textContent = `[${now}] ${message}\n${logBox.textContent}`;
}

async function api(path, options = {}) {
  const headers = options.headers || {};
  headers["Content-Type"] = "application/json";
  if (state.token) {
    headers["Authorization"] = `Bearer ${state.token}`;
  }

  const response = await fetch(path, { ...options, headers });
  const payload = await response.json();
  if (!response.ok) {
    throw new Error(`${payload.error}: ${payload.message}`);
  }
  return payload;
}

function getText(id) {
  return document.getElementById(id).value.trim();
}

function ensureToken() {
  if (!state.token) {
    throw new Error("请先登录");
  }
}

document.getElementById("registerBtn").addEventListener("click", async () => {
  try {
    const payload = await api("/api/auth/register", {
      method: "POST",
      body: JSON.stringify({
        username: getText("username"),
        password: getText("password"),
      }),
    });
    state.token = payload.sessionToken;
    document.getElementById("sessionView").textContent = `会话: ${state.token}`;
    log("注册成功并已登录。");
  } catch (error) {
    log(`注册失败: ${error.message}`);
  }
});

document.getElementById("loginBtn").addEventListener("click", async () => {
  try {
    const payload = await api("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({
        username: getText("username"),
        password: getText("password"),
      }),
    });
    state.token = payload.sessionToken;
    document.getElementById("sessionView").textContent = `会话: ${state.token}`;
    log("登录成功。");
  } catch (error) {
    log(`登录失败: ${error.message}`);
  }
});

document.getElementById("bindBtn").addEventListener("click", async () => {
  try {
    ensureToken();
    const payload = await api("/api/bind/request", {
      method: "POST",
      body: JSON.stringify({}),
    });
    document.getElementById("bindCodeView").textContent = `绑定码: ${payload.bindCode}`;
    log("绑定码已生成，请在游戏执行 /shop bind <code>。");
  } catch (error) {
    log(`生成绑定码失败: ${error.message}`);
  }
});

document.getElementById("walletBtn").addEventListener("click", async () => {
  try {
    ensureToken();
    const payload = await api("/api/wallet", { method: "GET" });
    document.getElementById("walletView").textContent =
      `ShopCoin: ${payload.shopCoin} | GameCoin: ${payload.gameCoin}`;
    log("钱包已刷新。");
  } catch (error) {
    log(`钱包刷新失败: ${error.message}`);
  }
});

document.getElementById("redeemBtn").addEventListener("click", async () => {
  try {
    ensureToken();
    const payload = await api("/api/redeem/use", {
      method: "POST",
      body: JSON.stringify({ code: getText("redeemCode") }),
    });
    log(`兑换结果: ${payload.status}`);
    document.getElementById("walletView").textContent =
      `ShopCoin: ${payload.shopCoin} | GameCoin: ${payload.gameCoin}`;
  } catch (error) {
    log(`兑换失败: ${error.message}`);
  }
});

document.getElementById("productsBtn").addEventListener("click", async () => {
  try {
    const payload = await api("/api/products", { method: "GET" });
    const list = document.getElementById("productList");
    list.innerHTML = "";
    payload.products.forEach((product) => {
      const div = document.createElement("div");
      div.className = "product-item";
      div.textContent =
        `ID:${product.id} | ${product.title} | ${product.price} ${product.currency}`;
      list.appendChild(div);
    });
    log(`已加载 ${payload.products.length} 个商品。`);
  } catch (error) {
    log(`加载商品失败: ${error.message}`);
  }
});

document.getElementById("orderBtn").addEventListener("click", async () => {
  try {
    ensureToken();
    const payload = await api("/api/orders", {
      method: "POST",
      body: JSON.stringify({
        productId: Number(getText("productId")),
        quantity: Number(getText("quantity") || 1),
        idempotencyKey: crypto.randomUUID(),
      }),
    });
    document.getElementById("orderView").textContent =
      `订单 ${payload.orderNo} | ${payload.totalAmount} ${payload.currency} | ${payload.state}`;
    log(`订单创建成功: ${payload.orderNo}`);
  } catch (error) {
    log(`下单失败: ${error.message}`);
  }
});

function renderListings(listings) {
  const marketList = document.getElementById("marketList");
  marketList.innerHTML = "";
  listings.forEach((listing) => {
    const div = document.createElement("div");
    div.className = "product-item";
    div.textContent =
      `上架ID:${listing.id} | 卖家:${listing.sellerName} | ${listing.itemMaterial} x${listing.quantity} | `
      + `${listing.price} ${listing.currency} | 状态:${listing.status}`;
    marketList.appendChild(div);
  });
}

document.getElementById("marketListBtn").addEventListener("click", async () => {
  try {
    const payload = await api("/api/market/listings?limit=100", { method: "GET" });
    renderListings(payload.listings);
    document.getElementById("marketView").textContent = `市场活跃上架: ${payload.listings.length}`;
    log(`已加载市场上架 ${payload.listings.length} 条。`);
  } catch (error) {
    log(`加载市场列表失败: ${error.message}`);
  }
});

document.getElementById("marketMineBtn").addEventListener("click", async () => {
  try {
    ensureToken();
    const payload = await api("/api/market/listings?mine=true&limit=100", { method: "GET" });
    renderListings(payload.listings);
    document.getElementById("marketView").textContent = `我的上架记录: ${payload.listings.length}`;
    log(`已加载我的上架 ${payload.listings.length} 条。`);
  } catch (error) {
    log(`加载我的上架失败: ${error.message}`);
  }
});

document.getElementById("marketBuyBtn").addEventListener("click", async () => {
  try {
    ensureToken();
    const listingId = Number(getText("marketBuyId"));
    const payload = await api("/api/market/buy", {
      method: "POST",
      body: JSON.stringify({
        listingId,
        idempotencyKey: crypto.randomUUID(),
      }),
    });
    document.getElementById("marketView").textContent =
      `购买完成: 上架 ${payload.listingId}，价格 ${payload.totalPrice} ${payload.currency}`;
    log(`市场购买成功，tradeId=${payload.tradeId} state=${payload.state}`);
  } catch (error) {
    log(`购买上架失败: ${error.message}`);
  }
});

document.getElementById("marketUnlistBtn").addEventListener("click", async () => {
  try {
    ensureToken();
    const listingId = Number(getText("marketUnlistId"));
    const payload = await api("/api/market/unlist", {
      method: "POST",
      body: JSON.stringify({ listingId }),
    });
    document.getElementById("marketView").textContent =
      `下架成功: 上架 ${payload.listingId} 已进入退回队列`;
    log(`下架成功，listing=${payload.listingId}`);
  } catch (error) {
    log(`下架失败: ${error.message}`);
  }
});
