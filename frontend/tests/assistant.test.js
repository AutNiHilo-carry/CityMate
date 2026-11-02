import test from "node:test";
import assert from "node:assert/strict";
import { questionBody, sourcePath, answerState } from "../scripts/assistant-helpers.js";
import { assistantPage } from "../scripts/assistant.js";

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
