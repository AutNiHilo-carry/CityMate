import test from "node:test";
import assert from "node:assert/strict";
import { pageVm, deferred } from "./page-harness.js";

const voucher = { id: "10", shopId: "4", title: "old", type: 0, payValue: 500 };
const preview = { voucherId: "10", shopId: "4", type: 0, title: "new", rules: "rule",
  payCents: 500, valueCents: 1000, availableAtQuery: true };

test("opening a voucher confirmation refreshes price and rules without writing", async () => {
  const calls=[];
  const vm=pageVm(async (path, options) => { calls.push({path,options}); return {data:preview}; });
  vm.user={id:"1"};
  await vm.claim(voucher);
  assert.equal(calls.length,1);
  assert.equal(calls[0].path,"/voucher/preview");
  assert.equal(calls[0].options.method,undefined);
  assert.equal(vm.dialog.voucher.title,"new");
  assert.equal(vm.dialog.voucher.rules,"rule");
});

test("double confirmation sends only one POST and repeated calls with no dialog are harmless", async () => {
  const pending=deferred();
  let posts=0;
  let previews=0;
  const vm=pageVm(async (path, options) => {
    if (options?.method === "POST") { posts++; return {data:"42"}; }
    return {data: ++previews===1 ? preview : await pending.promise};
  });
  vm.user={id:"1"};
  await vm.claim(voucher);
  const first=vm.confirmClaim();
  await vm.confirmClaim();
  pending.resolve(preview);
  await first;
  await vm.confirmClaim();
  assert.equal(posts,1);
  assert.equal(vm.navigation[0],"/orders.html");
});

test("updated voucher terms require another explicit confirmation", async () => {
  let reads=0, posts=0;
  const vm=pageVm(async (_,options) => {
    if(options?.method === "POST") { posts++; return {data:"42"}; }
    return {data: ++reads === 1 ? preview : {...preview,payCents:600}};
  });
  vm.user={id:"1"};
  await vm.claim(voucher);
  await vm.confirmClaim();
  assert.equal(posts,0);
  assert.equal(vm.dialog.voucher.payValue,600);
  await vm.confirmClaim();
  assert.equal(posts,1);
});

test("closed dialog or changed user cannot submit after a pending recheck", async () => {
  for(const close of [true,false]) {
    const pending=deferred();
    let reads=0,posts=0;
    const vm=pageVm(async (_,options) => {
      if(options?.method === "POST") posts++;
      return {data: ++reads===1 ? preview : await pending.promise};
    });
    vm.user={id:"1"};
    await vm.claim(voucher);
    const submit=vm.confirmClaim();
    if(close) vm.dialog=null; else vm.user={id:"2"};
    pending.resolve(preview);
    await submit;
    assert.equal(posts,0);
  }
});

test("precheck failures never enter uncertain-submission recovery", async () => {
  const paths=[];
  const vm=pageVm(async path => {
    paths.push(path);
    if(paths.length===1) return {data:preview};
    throw new Error("offline");
  });
  vm.user={id:"1"};
  await vm.claim(voucher);
  await vm.confirmClaim();
  assert.deepEqual(paths,["/voucher/preview","/voucher/preview"]);
  assert.ok(vm.dialog);
});

test("unknown submission makes a read-only check and never retries POST", async () => {
  const methods=[];
  const vm=pageVm(async (path,options) => {
    methods.push(options?.method || "GET");
    if(options?.method === "POST") throw new Error("timeout");
    return {data:path.endsWith("/preview") ? preview : null};
  });
  vm.user={id:"1"};
  await vm.claim(voucher);
  await vm.confirmClaim();
  assert.deepEqual(methods,["GET","GET","POST","GET"]);
  assert.equal(vm.navigation.length,0);
});

test("order pagination cannot change page during a request and rolls back failed navigation", async () => {
  const vm=pageVm(async () => {throw new Error("offline");});
  vm.orderLoading=true;
  await vm.orderPageTo(2);
  assert.equal(vm.orderPage,1);
  vm.orderLoading=false;
  await vm.orderPageTo(2);
  assert.equal(vm.orderPage,1);
  assert.equal(vm.orderError,"offline");
});

test("cancel confirmation is guarded against double invocation", async () => {
  const pending=deferred();
  let posts=0;
  const vm=pageVm(async (_,options) => {
    if(options?.method === "POST") {posts++; await pending.promise;}
    return {data:[],total:0};
  });
  vm.dialog={kind:"cancel",order:{order_id:"42"}};
  const first=vm.confirmCancel();
  await vm.confirmCancel();
  pending.resolve();
  await first;
  assert.equal(posts,1);
  assert.equal(vm.cancelBusy,false);
});

test("orders, profile and login pages never highlight discovery as the current page", () => {
  const vm=pageVm(async()=>({data:[]}));
  for(const page of ["orders","profile","login","publish"]){
    vm.page=page;
    assert.equal(vm.activeNav,"");
  }
  vm.page="home";
  assert.equal(vm.activeNav,"home");
});
