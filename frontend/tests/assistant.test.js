import test from "node:test";
import assert from "node:assert/strict";
import { questionBody, sourcePath, answerState, observationCards, claimPreview, orderState } from "../scripts/assistant-helpers.js";
import { assistantPage } from "../scripts/assistant.js";

const card = { shopId: "4", voucherId: "10" };
const preview = { shopId: "4", voucherId: "10", type: 0, title: "演示券", rules: "仅演示",
  payCents: 500, valueCents: 1000, availableAtQuery: true };
function claimVm(api) {
  const vm = { ...assistantPage.data(), user: { id: "1" }, userReady: true, destroyed: false, api };
  for (const name of ["prepareClaim", "confirmClaim", "refreshClaim"]) vm[name] = assistantPage.methods[name].bind(vm);
  return vm;
}

test("claim preview validates identity, types and monetary units", () => {
  assert.equal(claimPreview(preview, card).payCents, 500);
  assert.throws(() => claimPreview({ ...preview, voucherId: "11" }, card));
  assert.throws(() => claimPreview({ ...preview, payCents: "500" }, card));
  assert.throws(() => claimPreview({ ...preview, type: 8 }, card));
  assert.match(orderState(1), /待支付/);
  assert.match(orderState(2), /不代表真实收款/);
});

test("opening a candidate only previews and never submits a claim", async () => {
  const calls = [];
  const vm = claimVm(async (path, options) => { calls.push({ path, options }); return { data: preview }; });
  await vm.prepareClaim(card);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].path, "/assistant/claim-preview");
  assert.equal(calls[0].options.method, undefined);
  assert.equal(vm.claimDialog.preview.payCents, 500);
});

test("changed rules require another confirmation without sending POST", async () => {
  const calls = [];
  const vm = claimVm(async (path, options) => { calls.push({ path, options }); return { data: { ...preview, payCents: 600 } }; });
  vm.claimDialog = { card, preview, owner: "1", error: "" };
  await vm.confirmClaim();
  assert.match(vm.claimDialog.error, /已变化/);
  assert.equal(calls.filter(row => row.options.method === "POST").length, 0);
});

test("explicit confirmation sends one POST and reads an unpaid order", async () => {
  const calls = [];
  const vm = claimVm(async (path, options) => {
    calls.push({ path, options });
    return { data: path.includes("claim-preview") ? preview : options?.method === "POST" ? "9223372036854775807" : { status: 1 } };
  });
  vm.claimDialog = { card, preview, owner: "1", error: "" };
  await vm.confirmClaim();
  assert.equal(calls.filter(row => row.options?.method === "POST").length, 1);
  assert.equal(calls[1].path, "/voucher-order/ordinary/10");
  assert.equal(vm.claimResult.orderId, "9223372036854775807");
  assert.match(vm.claimResult.text, /待支付/);
  assert.equal(vm.claimDialog, null);
});

test("a timed out submission stays uncertain and is never automatically retried", async () => {
  const calls = [];
  const vm = claimVm(async (path, options) => {
    calls.push({ path, options });
    if (options?.method === "POST") throw new Error("timeout");
    return { data: path.includes("claim-preview") ? preview : null };
  });
  vm.claimDialog = { card, preview, owner: "1", error: "" };
  await vm.confirmClaim();
  assert.equal(vm.claimResult.uncertain, true);
  await vm.prepareClaim(card);
  await vm.refreshClaim();
  assert.equal(calls.filter(row => row.options?.method === "POST").length, 1);
  assert.match(vm.claimResult.text, /不要重复提交/);
});

test("seckill SUCCESS is described as persisted rather than paid", async () => {
  const vm = claimVm(async () => ({ data: { state: "SUCCESS" } }));
  vm.claimResult = { orderId: "123", voucherId: "10", type: 1, owner: "1", pending: true, attempts: 0, checking: false };
  await vm.refreshClaim();
  assert.match(vm.claimResult.text, /不表示已付款/);
  assert.equal(vm.claimResult.pending, false);
});

test("an explicit business rejection stays rejected rather than claiming acceptance", async () => {
  const vm = claimVm(async (path, options) => {
    if (options?.method === "POST") { const error = new Error("库存不足"); error.status = 200; throw error; }
    return { data: preview };
  });
  vm.claimDialog = { card, preview, owner: "1", error: "" };
  await vm.confirmClaim();
  assert.equal(vm.claimResult, null);
  assert.equal(vm.claimDialog.error, "库存不足");
});

test("switching identity during preview cannot create a confirmation for another user", async () => {
  let release;
  const vm = claimVm(() => new Promise(resolve => { release = resolve; }));
  const pending = vm.prepareClaim(card);
  vm.user = { id: "2" };
  release({ data: preview });
  await pending;
  assert.equal(vm.claimDialog, null);
});

test("double clicking confirm cannot submit twice while the fresh check is pending", async () => {
  let release;
  let checks = 0;
  const vm = claimVm(() => { checks++; return new Promise(resolve => { release = resolve; }); });
  vm.claimDialog = { card, preview, owner: "1", error: "" };
  const first = vm.confirmClaim();
  await vm.confirmClaim();
  assert.equal(checks, 1);
  release({ data: { ...preview, availableAtQuery: false } });
  await first;
  assert.match(vm.claimDialog.error, /不可领取/);
  assert.equal(vm.claimResult, null);
});

test("automatic seckill polling stops after the tenth observation without resubmitting", async () => {
  let reads = 0;
  const vm = claimVm(async (path, options) => {
    reads++; assert.equal(options.method, undefined); return { data: { state: "PROCESSING" } };
  });
  vm.claimResult = { orderId: "123", voucherId: "10", type: 1, owner: "1", pending: true,
    attempts: 9, checking: false };
  await vm.refreshClaim();
  assert.equal(reads, 1);
  assert.equal(vm.claimResult.attempts, 10);
  assert.equal(vm.claimTimer, undefined);
  assert.match(vm.claimResult.text, /仍在处理中/);
});

test("agent mode chooses read-only endpoint and retry retains its original mode", async () => {
  const calls = [];
  const vm = { ...assistantPage.data(), user: { id: "1" }, userReady: true,
    requestSequence: 0, scrollToLatest() {}, api: async (path, options) => {
      calls.push({ path, options });
      return { data: { mode: "SINGLE_READ_ONLY_AGENT", status: "NEEDS_CLARIFICATION",
        answered: false, answer: "请补充商圈", sources: [], steps: [{ tool: "listAreas", status: "OK" }] } };
    } };
  const body = { query: "附近有什么店", limit: 3, shopId: "10" };
  await assistantPage.methods.send.call(vm, body, "店铺10", "agent");
  assert.equal(calls[0].path, "/agent/query");
  assert.equal(calls[0].options.body.limit, undefined);
  assert.equal(body.limit, 3);
  assert.equal(vm.messages[0].mode, "agent");
  assert.equal(vm.messages[0].state.answered, false);
  assert.equal(vm.messages[0].steps.length, 1);
  assert.match(vm.messages[0].notice, /没有.*领取/);
  vm.mode = "rules";
  const original = vm.messages[0];
  await assistantPage.methods.send.call(vm, original.body, original.scope, original.mode);
  assert.equal(calls[1].path, "/agent/query");
});

test("agent labels distinguish successful queries from limits and clarification", () => {
  assert.equal(answerState({ mode: "SINGLE_READ_ONLY_AGENT", status: "ANSWERED", answered: true }).label, "已为你查询");
  for (const status of ["NEEDS_CLARIFICATION", "STEP_LIMIT", "CONTEXT_LIMIT"]) {
    assert.equal(answerState({ status }).answered, false);
    assert.equal(answerState({ status }).retry, false);
  }
  assert.equal(assistantPage.methods.toolLabel("claimVoucher"), "查询资料");
  assert.ok(!assistantPage.template.includes("v-html"));
});

test("business cards use whitelisted observations with exact IDs and monetary units", () => {
  const observations = [{ tool: "searchShops", result: { status: "OK", data: { items: [
    { shopId: "9223372036854775807", name: "火锅", avgPriceYuan: 50, area: "武林" },
    { shopId: "javascript:evil", name: "不能跳转" }] } } },
  { tool: "listShopVouchers", result: { status: "OK", data: { items: [
    { shopId: "10", voucherId: "17", title: "代金券", payCents: 1299 }] } } }];
  const cards = observationCards(observations);
  assert.equal(cards.length, 2);
  assert.equal(cards[0].href, "/shop-detail.html?id=9223372036854775807");
  assert.equal(cards[0].price, "人均约 ¥50");
  assert.equal(cards[1].price, "券价 ¥12.99");
  assert.deepEqual(observationCards([{ tool: "claimVoucher", result: observations[0].result }]), []);
  assert.deepEqual(observationCards([{ ...observations[0], result: { status: "UNAVAILABLE" } }]), []);
});

test("questions preserve long business IDs and reject malformed or oversized scopes", () => {
  assert.deepEqual(questionBody("  券的规则  ", "9223372036854775807", "17"),
    { query: "券的规则", limit: 3, shopId: "9223372036854775807", voucherId: "17" });
  assert.deepEqual(questionBody("平台规则"), { query: "平台规则", limit: 3 });
  for (const value of ["0", "-1", "1.2", "1e5", "1&userId=7", "9223372036854775808"]) {
    assert.throws(() => questionBody("查询规则", value));
  }
  assert.throws(() => questionBody(" "));
  assert.throws(() => questionBody("字".repeat(201)));
});

test("source links are built from known identifiers and never from model URLs", () => {
  assert.equal(sourcePath({ sourceKey: "platform-faq", version: 4,
    sourceUrl: "javascript:alert(1)" }), "/knowledge/sources/platform-faq?version=4");
  for (const source of [null, { sourceKey: "../admin", version: 1 },
    { sourceKey: "x?user=1", version: 1 }, { sourceKey: "x", version: 0 },
    { sourceKey: "x", version: "2" }, { sourceKey: "https://evil.test", version: 1 }]) {
    assert.equal(sourcePath(source), "");
  }
});

test("business success alone never renders a generated answer as successful", () => {
  assert.equal(answerState({ status: "MODEL_NOT_CONFIGURED", answered: false }).answered, false);
  assert.equal(answerState({ status: "ANSWERED", answered: false }).answered, false);
  assert.equal(answerState({ status: "ANSWERED", answered: true }).answered, true);
  assert.equal(answerState({ status: "NO_EVIDENCE" }).retry, false);
  assert.equal(answerState({ status: "STALE_EVIDENCE" }).retry, true);
  assert.equal(answerState({ status: "MODEL_TIMEOUT" }).retry, true);
});

test("a pending question prevents duplicate requests and retains the original scope", async () => {
  let release;
  let calls = 0;
  const pending = new Promise((resolve) => { release = resolve; });
  const vm = { ...assistantPage.data(), user: { id: "1" }, userReady: true,
    requestSequence: 0, destroyed: false, scrollToLatest() {},
    api: async () => { calls++; return pending; } };
  const send = assistantPage.methods.send.bind(vm);
  const first = send({ query: "付款状态", limit: 3 }, "平台规则");
  await send({ query: "重复提问", limit: 3 }, "其他店铺");
  assert.equal(calls, 1);
  release({ data: { status: "ANSWERED", answered: true, answer: "<img onerror=alert(1)>", sources: [] } });
  await first;
  assert.equal(vm.messages[0].scope, "平台规则");
  assert.equal(vm.messages[0].answer, "<img onerror=alert(1)>");
  assert.equal(vm.busy, false);
});

test("older voucher responses cannot replace the currently selected shop", async () => {
  let release;
  const vm = { ...assistantPage.data(), shopId: "1", voucherSequence: 0,
    api: () => new Promise((resolve) => { release = resolve; }) };
  const change = assistantPage.methods.changeShop.bind(vm);
  const pending = change();
  vm.shopId = "";
  await change();
  release({ data: [{ id: "9", status: 1 }] });
  await pending;
  assert.deepEqual(vm.vouchers, []);
  assert.equal(vm.voucherBusy, false);
});

test("failure restores controls and keeps a retry tied to the exact original question", async () => {
  const vm = { ...assistantPage.data(), user: { id: "1" }, userReady: true,
    requestSequence: 0, scrollToLatest() {}, api: async () => { throw new Error("请求超时"); } };
  const body = { query: "是否付款", limit: 3, shopId: "10" };
  await assistantPage.methods.send.call(vm, body, "店铺10");
  assert.equal(vm.busy, false);
  assert.equal(vm.messages[0].state.retry, true);
  assert.deepEqual(vm.messages[0].body, body);
  assert.equal(vm.messages[0].answer, "请求超时");
});
