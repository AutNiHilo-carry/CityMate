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
    return { label: "已根据资料回答", answered: true, retry: false };
  }
  const states = {
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
