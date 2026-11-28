import test from "node:test";
import assert from "node:assert/strict";
import {pageVm} from "./page-harness.js";

test("voucher header searches the current category and shop rather than leaving the page",async()=>{
  const calls=[];const vm=pageVm(async(path,options)=>{calls.push({path,options});return path.endsWith('/shops')?{data:{shops:[]}}:{data:[],total:0};});
  vm.offerType='1';vm.offerShopId='99';vm.keyword='  午市代金券  ';
  await vm.search();
  const read=calls.find(c=>c.path==='/voucher/catalog');
  assert.equal(read.options.params.keyword,'午市代金券');assert.equal(read.options.params.typeId,'1');assert.equal(read.options.params.shopId,'99');
  assert.equal(vm.navigation.length,0);assert.equal(vm.headerSearchLabel,'搜索优惠券或店铺');
});

test("shop header search keeps submitted budget and rating filters",async()=>{
  const vm=pageVm(async()=>({data:[],total:0}),{pathname:'/shop-list.html'});
  vm.catalogMaxPrice='100';vm.catalogMinScore='45';vm.keyword='茶餐厅';
  await vm.search();assert.equal(vm.catalogCriteria.keyword,'茶餐厅');assert.equal(vm.catalogCriteria.maxPrice,'100');assert.equal(vm.catalogCriteria.minScore,'45');
  assert.equal(vm.navigation.length,0);
});

test("global discovery search safely navigates to the shop catalog",()=>{
  const vm=pageVm(async()=>({data:[]}),{pathname:'/'});vm.keyword=' 火锅 & 茶 ';
  vm.search();const url=new URL(vm.navigation[0],'http://localhost');
  assert.equal(url.pathname,'/shop-list.html');assert.equal(url.searchParams.get('keyword'),'火锅 & 茶');
});

test("editing and login stay focused while browsing pages expose mobile navigation",()=>{
  const vm=pageVm(async()=>({data:[]}));
  for(const page of ['login','publish','experience','not-found']){vm.page=page;assert.equal(vm.showMobileNav,false);}
  for(const page of ['home','shops','shop','vouchers','orders','profile','assistant']){vm.page=page;assert.equal(vm.showMobileNav,true);}
});
