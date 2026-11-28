import test from "node:test";
import assert from "node:assert/strict";
import { pageVm, deferred } from "./page-harness.js";

test("selected shop remains explicit while options are missing or limited",()=>{
  const vm=pageVm(async()=>({data:[]}));
  vm.offerShopId='9007199254740993';
  assert.equal(vm.offerSelectedShopMissing,true);
  vm.offerShopOptions=[{id:'9007199254740993'}];assert.equal(vm.offerSelectedShopMissing,false);
  vm.offerShopId='';assert.equal(vm.offerSelectedShopMissing,false);
});

test("voucher URL contains submitted criteria and pagination ignores unsent edits",async()=>{
  const vm=pageVm(async path=>path.endsWith('/shops')?{data:{shops:[]}}:{data:[{id:1}],total:30},
    {browserUrl:'http://localhost/vouchers.html?ref=shared#offers'});
  vm.offerKeyword='  火锅 & 代金券  ';vm.offerType='1';vm.offerShopId='9007199254740993';
  await vm.loadOffers(true);
  const url=new URL(vm.replacedUrls[0],'http://localhost');
  assert.equal(url.searchParams.get('keyword'),'火锅 & 代金券');
  assert.equal(url.searchParams.get('shopId'),'9007199254740993');
  assert.equal(url.searchParams.get('type'),'1');assert.equal(url.searchParams.get('ref'),'shared');assert.equal(url.hash,'#offers');
  vm.offerKeyword='not submitted';await vm.loadOffers();assert.equal(vm.replacedUrls.length,1);
  await vm.resetOfferFilters();const reset=new URL(vm.replacedUrls.at(-1),'http://localhost');
  for(const key of ['keyword','type','shopId'])assert.equal(reset.searchParams.has(key),false);
});

test("shop-option retry does not clear voucher cards or repeat the catalog request",async()=>{
  let catalogCalls=0,fail=true;
  const vm=pageVm(async path=>{
    if(path.endsWith('/shops')){if(fail)throw new Error('unavailable');return {data:{shops:[{id:10,name:'餐厅'}]}};}
    catalogCalls++;return {data:[{id:1}],total:1};
  });
  await vm.loadOffers(true);assert.ok(vm.offerShopError);assert.equal(vm.offers.length,1);
  fail=false;await vm.loadOfferShops();assert.equal(vm.offerShopError,'');assert.equal(vm.offerShopOptions.length,1);
  assert.equal(vm.offers.length,1);assert.equal(catalogCalls,1);
});

test("late shop-option failures cannot overwrite newer successful options",async()=>{
  const pending=deferred();let reads=0;
  const vm=pageVm(async()=>++reads===1?pending.promise:{data:{shops:[{id:20}],hasMore:true}});
  vm.offerCriteria={typeId:'1'};const first=vm.loadOfferShops();
  vm.offerCriteria={typeId:'2'};await vm.loadOfferShops();
  pending.reject(new Error('old failure'));await first;
  assert.equal(vm.offerShopOptions[0].id,20);assert.equal(vm.offerShopError,'');assert.equal(vm.offerShopLoading,false);
});
