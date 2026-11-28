import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";

// Load the actual Vue page methods with a transport stub; no browser or network.
function pageVm(api) {
  let options;
  const source = readFileSync(new URL("../scripts/app.js", import.meta.url), "utf8")
    .replace(/^import .*;\r?\n/gm, "");
  runInNewContext(source, {
    URLSearchParams,
    location: { search: "", pathname: "/vouchers.html" },
    createApi: () => api,
    images() {}, imagePath() {}, money() {}, safeReturn() {},
    window: { addEventListener() {} },
    Vue: function (value) { options = value; },
  });
  const vm = { ...options.data };
  for (const name of ["loadOffers", "changeOfferType", "resetOfferFilters", "syncOfferUrl", "loadOfferShops"]) {
    vm[name] = options.methods[name].bind(vm);
  }
  return vm;
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

test("voucher pagination retains submitted criteria despite unsubmitted input edits", async () => {
  const calls = [];
  const vm = pageVm(async (path, options) => {
    if (path.endsWith("/shops")) return { data: { shops: [] } };
    calls.push(options.params);
    return { data: [{ id: calls.length }], total: 25 };
  });
  vm.offerKeyword = " 火锅 ";
  vm.offerType = "1";
  vm.offerShopId = "9007199254740993";
  await vm.loadOffers(true);
  vm.offerKeyword = "尚未提交的新关键词";
  await vm.loadOffers();
  assert.equal(calls[0].keyword, "火锅");
  assert.equal(calls[1].keyword, "火锅");
  assert.equal(calls[1].shopId, "9007199254740993");
  assert.equal(calls[1].typeId, "1");
  assert.equal(calls[1].page, 2);
  assert.equal(vm.offers.length, 2);
});

test("late catalog responses cannot replace a newer category search", async () => {
  const first = deferred();
  const vm = pageVm(async (path, options) => {
    if (path.endsWith("/shops")) return { data: { shops: [] } };
    if (!options.params.typeId) return first.promise;
    return { data: [{ id: "new" }], total: 1 };
  });
  const old = vm.loadOffers(true);
  vm.offerType = "2";
  await vm.loadOffers(true);
  first.resolve({ data: [{ id: "old" }], total: 99 });
  await old;
  assert.equal(vm.offers[0].id, "new");
  assert.equal(vm.offerTotal, 1);
  assert.equal(vm.offerLoading, false);
});

test("failed load-more keeps its page for retry and never appends duplicates", async () => {
  let fail = false;
  const pages = [];
  const vm = pageVm(async (path, options) => {
    if (path.endsWith("/shops")) return { data: { shops: [] } };
    pages.push(options.params.page);
    if (fail) throw new Error("network unavailable");
    return { data: [{ id: options.params.page }], total: 25 };
  });
  await vm.loadOffers(true);
  fail = true;
  await vm.loadOffers();
  assert.equal(vm.offerPage, 2);
  assert.equal(vm.offers.length, 1);
  fail = false;
  await vm.loadOffers();
  assert.deepEqual(pages, [1, 2, 2]);
  assert.equal(vm.offers.length, 2);
  assert.equal(vm.offerError, "");
});

test("changing category clears the previous shop and ignores old shop options", async () => {
  const firstOptions = deferred();
  let searches = 0;
  const vm = pageVm(async (path, options) => {
    if (path.endsWith("/shops")) {
      return options.params.typeId === "1"
        ? firstOptions.promise : { data: { shops: [{ id: "22", name: "new" }] } };
    }
    searches++;
    if (searches === 2) assert.equal(options.params.shopId, "");
    return { data: [], total: 0 };
  });
  vm.offerType = "1";
  vm.offerShopId = "11";
  await vm.loadOffers(true);
  await vm.changeOfferType("2");
  firstOptions.resolve({ data: { shops: [{ id: "11", name: "old" }] } });
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(vm.offerShopId, "");
  assert.equal(vm.offerShopOptions[0].id, "22");
});

test("clearing filters starts again at page one without retaining old cards", async () => {
  const vm = pageVm(async (path, options) => {
    if (path.endsWith("/shops")) return { data: { shops: [] } };
    assert.equal(options.params.page, 1);
    assert.equal(options.params.keyword, "");
    assert.equal(options.params.shopId, "");
    assert.equal(options.params.typeId, "");
    return { data: [{ id: "reset" }], total: 1 };
  });
  vm.offerKeyword = "old";
  vm.offerType = "2";
  vm.offerShopId = "11";
  vm.offers = [{ id: "old" }];
  vm.offerPage = 3;
  await vm.resetOfferFilters();
  assert.equal(vm.offers.length, 1);
  assert.equal(vm.offers[0].id, "reset");
  assert.equal(vm.offerHasMore, false);
});

test("overlapping database pages cannot render duplicate voucher cards", async () => {
  const vm = pageVm(async (path, options) => {
    if (path.endsWith("/shops")) return { data: { shops: [] } };
    return {data: options.params.page === 1 ? [{id:"1"}] : [{id:"1"},{id:"2"}],total:25};
  });
  await vm.loadOffers(true);
  await vm.loadOffers();
  assert.equal(vm.offers.length,2);
  assert.equal(vm.offers[1].id,"2");
});
