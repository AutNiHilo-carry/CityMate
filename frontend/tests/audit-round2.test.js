import test from "node:test";
import assert from "node:assert/strict";
import { pageVm, deferred } from "./page-harness.js";

test("profile notes still load when its streak query is unavailable",async()=>{
  let notes=0;
  const vm=pageVm(async path=>{
    if(path==='/user/sign/count')throw new Error('unavailable');
    if(path==='/blog/of/me'){notes++;return {data:[{id:1}]};}
    throw new Error('unexpected path '+path);
  },{pathname:'/info.html'});
  vm.user={id:'1',nickName:'owner'};
  await vm.loadProfile();
  assert.equal(notes,1);assert.equal(vm.blogs.length,1);assert.equal(vm.pageError,'');
  assert.ok(vm.signCountError);
});

test("older streak failure cannot replace a successful retry",async()=>{
  const pending=deferred();let calls=0;
  const vm=pageVm(async()=>++calls===1?pending.promise:{data:7});
  const first=vm.loadSignCount();await vm.loadSignCount();
  pending.reject(new Error('old failure'));await first;
  assert.equal(vm.signCount,7);assert.equal(vm.signCountError,'');assert.equal(vm.signCountLoading,false);
});

test("home promotion requires explicit server availability rather than a missing field",async()=>{
  const vm=pageVm(async path=>{
    if(path==="/shop/catalog")return {data:[{id:10}]};
    if(path.startsWith('/voucher/list/'))return {data:[{id:'unknown',type:0},{id:'closed',type:0,orderable:false},{id:'available',type:0,orderable:true}]};
    return {data:[]};
  },{pathname:"/"});
  await vm.loadHome();assert.equal(vm.homeOffer.id,'available');
});

test("switching feeds ignores an older pending hot response", async () => {
  const hot = deferred();
  const vm = pageVm(async path => path === "/blog/hot" ? hot.promise : {data:{list:[{id:"follow"}],minTime:20,offset:1}}, {pathname:"/"});
  vm.user={id:"1"};
  const first=vm.loadBlogs(true);
  await vm.changeFeed("follow");
  hot.resolve({data:[{id:"hot"}]});await first;
  assert.equal(vm.blogs.length,1);assert.equal(vm.blogs[0].id,"follow");
  assert.equal(vm.feedLastId,20);assert.equal(vm.blogLoading,false);
});

test("late failed feed response cannot display an error on the newer feed",async()=>{
  const hot=deferred();
  const vm=pageVm(async path=>path==="/blog/hot"?hot.promise:{data:{list:[],minTime:20,offset:0}},{pathname:"/"});
  vm.user={id:"1"};const first=vm.loadBlogs(true);await vm.changeFeed("follow");
  hot.reject(new Error("old failure"));await first;
  assert.equal(vm.pageError,"");assert.equal(vm.blogHasMore,false);
});

test("feed retries clear old errors, retain page on failure and deduplicate IDs",async()=>{
  let count=0;
  const vm=pageVm(async()=>{
    if(++count===2)throw new Error("network failure");
    return {data:count===1?[{id:1}]:[{id:"1"},{id:2}]};
  },{pathname:"/"});
  await vm.loadBlogs(true);await vm.loadBlogs();assert.equal(vm.blogPage,2);
  await vm.loadBlogs();assert.equal(vm.pageError,"");assert.equal(vm.blogPage,3);
  assert.equal(vm.blogs.length,2);
});

test("successful cancellation forces a fresh list despite a pending old refresh",async()=>{
  const stale=deferred();let reads=0,posts=0;
  const vm=pageVm(async(_,options)=>{
    if(options?.method==="POST"){posts++;return {success:true};}
    if(++reads===1)return stale.promise;
    return {data:[{order_id:"1",status:4}],total:1};
  });
  vm.user={id:"1"};const refresh=vm.loadOrders();
  vm.cancelOrder({order_id:"1",status:1,voucher_type:0});await vm.confirmCancel();
  stale.resolve({data:[{order_id:"1",status:1}],total:1});await refresh;
  assert.equal(posts,1);assert.equal(vm.orders[0].status,4);
});

test("uncertain cancellation does not automatically retry and prompts status verification",async()=>{
  let posts=0;
  const vm=pageVm(async()=>{posts++;throw Object.assign(new Error("timeout"),{status:0});});
  vm.user={id:"1"};vm.cancelOrder({order_id:"1",status:1,voucher_type:0});await vm.confirmCancel();
  assert.equal(posts,1);assert.match(vm.toast.message,/未确认/);
});

test("successful sign stays signed when the separate streak query fails",async()=>{
  let posts=0;
  const vm=pageVm(async(_,options)=>{
    if(options?.method==="POST"){posts++;return {success:true};}
    throw new Error("streak unavailable");
  });
  vm.user={id:"1"};await vm.sign();await vm.sign();
  assert.equal(vm.signed,true);assert.equal(posts,1);assert.match(vm.toast.message,/签到成功/);
});
