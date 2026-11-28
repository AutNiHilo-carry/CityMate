export function questionBody(query, shopId = "", voucherId = "") {
  const text = String(query || "").trim();
  if (text.length < 2 || text.length > 200) {
    throw new Error("请输入 2～200 字的问题");
  }
  const body = { query: text, limit: 3 };
  for (const [name, value] of Object.entries({ shopId, voucherId })) {
    if (value !== "" && value != null) {
      const id = String(value);
      if (!/^[1-9]\d{0,18}$/.test(id) || BigInt(id) > 9223372036854775807n) {
        throw new Error("店铺或优惠券编号无效，请重新选择");
      }
      body[name] = id;
    }
  }
  return body;
}

// Never trust a model-provided URL, even if a future backend includes one.
export function sourcePath(source) {
  if (!source || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(source.sourceKey || "")
      || !Number.isSafeInteger(source.version) || source.version < 1) {
    return "";
  }
  return "/knowledge/sources/" + encodeURIComponent(source.sourceKey)
    + "?version=" + source.version;
}

export function answerState(data) {
  if (data && data.status === "ANSWERED" && data.answered === true) {
    return { label: "已为你查询", answered: true, retry: false };
  }
  const states = {
    NEEDS_CLARIFICATION: ["请补充查询条件", false],
    STEP_LIMIT: ["本次查询步骤较多，请缩小范围", false],
    CONTEXT_LIMIT: ["查询内容较多，请缩小范围", false],
    INVALID_TOOL_RESULT: ["查询结果暂不可用", true],
    NO_EVIDENCE: ["暂未找到依据", false],
    INSUFFICIENT_EVIDENCE: ["现有资料不足", false],
    STALE_EVIDENCE: ["资料已更新，请重试", true],
    MODEL_NOT_CONFIGURED: ["助手尚未启用，可查看原文", false],
    INVALID_MODEL_RESPONSE: ["暂时无法提供可靠回答", true],
    MODEL_TIMEOUT: ["回答超时", true],
    MODEL_RATE_LIMITED: ["助手繁忙，请稍后重试", true],
    MODEL_UNAVAILABLE: ["助手暂时不可用", true],
    BUSY: ["助手繁忙，请稍后重试", true],
  };
  const state = states[data && data.status] || ["暂时无法回答", true];
  return { label: state[0], answered: false, retry: state[1] };
}

// Business cards use server observations rather than IDs or URLs generated in prose.
export function observationCards(observations) {
  if (!Array.isArray(observations)) return [];
  const cards = [];
  const seen = new Set();
  for (const observation of observations.slice(0, 8)) {
    if (observation?.result?.status !== "OK"
        || !["searchShops", "listShopVouchers"].includes(observation.tool)) continue;
    const items = observation.result.data?.items;
    if (!Array.isArray(items)) continue;
    for (const item of items.slice(0, 20)) {
      if (!item || typeof item !== "object") continue;
      const shop = observation.tool === "searchShops";
      const id = String(shop ? item.shopId : item.voucherId);
      const shopId = String(item.shopId);
      const validId = (value) => /^[1-9]\d{0,18}$/.test(value)
        && BigInt(value) <= 9223372036854775807n;
      if (!validId(id) || !validId(shopId)) continue;
      const key = observation.tool + ":" + id;
      if (seen.has(key)) continue;
      seen.add(key);
      const rawPrice = shop ? item.avgPriceYuan : item.payCents;
      const amount = Number(rawPrice);
      const numericPrice = typeof rawPrice === "number" || (typeof rawPrice === "string" && /^\d+(?:\.\d+)?$/.test(rawPrice.trim()));
      const validPrice = numericPrice && Number.isFinite(amount) && amount >= 0
        && (shop || Number.isSafeInteger(amount));
      const price = shop ? amount : amount / 100;
      cards.push({ key, kind: shop ? "shop" : "voucher", title: String((shop ? item.name : item.title) || "查看详情"),
        shopId, voucherId: shop ? "" : id,
        description: shop ? String(item.area || item.address || "") : "优惠券 · 可领状态与资格以详情页为准",
        price: validPrice ? (shop ? "人均约 ¥" : "券价 ¥") + price : "",
        href: "/shop-detail.html?id=" + encodeURIComponent(shopId) });
    }
  }
  if (cards.length <= 20) return cards;
  const selected = new Set([
    ...cards.filter(card => card.kind === "shop").slice(0, 10),
    ...cards.filter(card => card.kind === "voucher").slice(0, 10),
  ].map(card => card.key));
  for (const card of cards) { if (selected.size >= 20) break; selected.add(card.key); }
  return cards.filter(card => selected.has(card.key));
}

export function claimPreview(data, card) {
  if (!data || String(data.shopId) !== card.shopId || String(data.voucherId) !== card.voucherId
      || ![0, 1].includes(data.type) || !Number.isSafeInteger(data.payCents) || data.payCents < 0
      || !Number.isSafeInteger(data.valueCents) || data.valueCents < 0
      || typeof data.availableAtQuery !== "boolean") throw new Error("优惠券信息无法核对，请重新查询");
  return { ...data, shopId: card.shopId, voucherId: card.voucherId };
}

// Only compare tool-backed facts. No claimed distance, rating, availability or savings.
export function shopComparison(cards) {
  if (!Array.isArray(cards)) return [];
  return cards.filter(card => card.kind === 'shop').slice(0, 3).map(shop => {
    const vouchers = cards.filter(card => card.kind === 'voucher' && card.shopId === shop.shopId);
    return {...shop, couponSummary: vouchers.length ? '本次查到 ' + vouchers.length + ' 张券，领取前需核对' : '未查询到券信息，可继续查券'};
  });
}

export function claimFingerprint(value) {
  return JSON.stringify([value.shopId, value.voucherId, value.type, value.title, value.rules,
    value.payCents, value.valueCents, value.beginTime, value.endTime]);
}

export function orderState(status) {
  return ({ 1: "订单已创建，待支付；本次未付款", 2: "订单已领取或标记完成，不代表真实收款",
    3: "订单已核销", 4: "订单已取消", 5: "订单退款中", 6: "订单已退款" })[status]
    || "订单状态待核对，请查看我的订单";
}
