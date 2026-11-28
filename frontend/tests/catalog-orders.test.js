import test from "node:test";
import assert from "node:assert/strict";
import { pageVm, deferred } from "./page-harness.js";

test("shop pagination retains submitted price, rating, keyword and area criteria",async()=>{
  const calls=[];
  const vm=pageVm(async(_,options)=>{calls.push(options.params);return {data:[{id:String(calls.length)}],total:25};});
  vm.catalogKeyword=" 火锅 ";vm.catalogArea=" 运河 ";vm.catalogMaxPrice="50";
  vm.catalogMinScore="45";vm.catalogHasVoucher=true;
  await vm.loadCatalog(true);
  vm.catalogKeyword="unsubmitted";vm.catalogArea="elsewhere";vm.catalogMaxPrice="200";
  await vm.loadCatalog();
  assert.equal(calls[1].keyword,"火锅");assert.equal(calls[1].area,"运河");
  assert.equal(calls[1].maxPrice,"50");assert.equal(calls[1].minScore,"45");
  assert.equal(calls[1].hasVoucher,true);assert.equal(calls[1].page,2);
});

test("late shop results cannot overwrite a newer filter and reset clears all controls",async()=>{
  const pending=deferred();let reads=0;
  const vm=pageVm(async()=>++reads===1?pending.promise:{data:[{id:"new"}],total:1});
  vm.catalogKeyword="old";const first=vm.loadCatalog(true);
  vm.catalogMaxPrice="50";vm.catalogHasVoucher=true;
  await vm.resetCatalogFilters();
  pending.resolve({data:[{id:"old"}],total:100});await first;
  assert.equal(vm.shops[0].id,"new");assert.equal(vm.catalogTotal,1);
  assert.equal(vm.catalogKeyword,"");assert.equal(vm.catalogMaxPrice,"");assert.equal(vm.catalogHasVoucher,false);
});

test("held orders use the server-side endpoint and ignore stale all-order results",async()=>{
  const pending=deferred(),paths=[];
  const vm=pageVm(async path=>{paths.push(path);return path.endsWith('/vouchers')?{data:[{order_id:'new'}],total:1}:pending.promise;});
  const first=vm.loadOrders();await vm.changeOrderScope('held');
  pending.resolve({data:[{order_id:'old'}],total:99});await first;
  assert.deepEqual(paths,['/voucher-order/mine','/voucher-order/mine/vouchers']);
  assert.equal(vm.orders[0].order_id,'new');assert.equal(vm.orderTotal,1);assert.equal(vm.orderPage,1);
});

test("a failed old-page navigation cannot roll back a newly selected order scope",async()=>{
  const pending=deferred();const vm=pageVm(async path=>path.endsWith('/vouchers')?{data:[],total:0}:pending.promise);
  vm.orderPage=3;const old=vm.orderPageTo(4);await vm.changeOrderScope('held');
  pending.reject(new Error('old request failed'));await old;
  assert.equal(vm.orderPage,1);assert.equal(vm.orderError,'');assert.equal(vm.orderScope,'held');
});

test("copying order numbers keeps every digit and permission errors stay actionable",async()=>{
  const vm=pageVm(async()=>({data:[]}));await vm.copyOrderId('9007199254740993');
  assert.deepEqual(vm.copiedOrderIds,['9007199254740993']);
  const denied=pageVm(async()=>({data:[]}),{clipboard:{writeText:async()=>{throw new Error('denied');}}});
  await denied.copyOrderId('9007199254740993');
  assert.match(denied.toast.message,/手动复制/);assert.equal(denied.copyingOrderId,'');
});

test("request SUCCESS describes order creation rather than successful payment",async()=>{
  const vm=pageVm(async path=>({data:path.includes('/requests/')?{state:'SUCCESS'}:[],total:0}),{search:'?request=9007199254740993'});
  await vm.watchRequest();
  assert.match(vm.requestMessage,/订单已创建/);assert.doesNotMatch(vm.requestMessage,/已付款|领取已确认/);
});
