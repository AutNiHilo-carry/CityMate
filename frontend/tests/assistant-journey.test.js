import test from "node:test";
import assert from "node:assert/strict";
import {assistantPage} from "../scripts/assistant.js";
import {observationCards} from "../scripts/assistant-helpers.js";
import {deferred} from "./page-harness.js";

function vmFor(api){
  const vm={...assistantPage.data(),user:{id:'1'},userReady:true,destroyed:false,
    requestSequence:0,shopSequence:0,voucherSequence:0,api,$refs:{},$nextTick(fn){fn();}};
  for(const [name,method]of Object.entries(assistantPage.methods))vm[name]=method.bind(vm);
  for(const [name,get]of Object.entries(assistantPage.computed))Object.defineProperty(vm,name,{get:()=>get.call(vm)});
  return vm;
}
const answer=text=>({data:{status:'ANSWERED',answered:true,answer:text,observations:[],sources:[]}});

test('switching accounts removes private conversation and ignores an old answer without unlocking a new request',async()=>{
  const first=deferred(),second=deferred();let reads=0;
  const vm=vmFor(()=>++reads===1?first.promise:second.promise);
  const old=vm.send({query:'我的订单'},'本人订单','agent');
  const previous=vm.user;vm.user={id:'2'};assistantPage.watch.user.call(vm,vm.user,previous);
  assert.equal(vm.messages.length,0);
  const current=vm.send({query:'用户2的问题'},'本人订单','agent');
  first.resolve(answer('用户1的私有订单'));await old;
  assert.equal(vm.busy,true);assert.equal(vm.messages.length,1);assert.equal(vm.messages[0].pending,true);
  second.resolve(answer('用户2的结果'));await current;
  assert.equal(vm.messages[0].answer,'用户2的结果');assert.equal(vm.busy,false);
});

test('pending claim action does not consume a new question draft',async()=>{
  let calls=0;const vm=vmFor(async()=>{calls++;return answer('unused');});
  vm.claimBusy=true;vm.draft='帮我看看优惠';await vm.submit();
  assert.equal(vm.draft,'帮我看看优惠');assert.equal(calls,0);
});

test('follow-up explicitly includes the original query and preserves its scope and mode',async()=>{
  const calls=[];const vm=vmFor(async(path,options)=>{calls.push({path,options});return answer('已查到');});
  vm.startFollowUp({owner:'1',body:{query:'找一家火锅店',shopId:'10',voucherId:'17'},scope:'原店铺',mode:'agent'});
  vm.shopId='20';vm.voucherId='30';vm.mode='rules';vm.draft='运河商圈，人均100元以内';
  await vm.submit();assert.equal(calls[0].path,'/agent/query');
  assert.equal(calls[0].options.body.query,'找一家火锅店\n补充：运河商圈，人均100元以内');
  assert.equal(calls[0].options.body.shopId,'10');assert.equal(calls[0].options.body.voucherId,'17');
  assert.equal(vm.messages[0].scope,'原店铺');assert.equal(vm.followUp,null);
});

test('follow-up length limits reject oversize input without silently truncating it',async()=>{
  let calls=0;const bodies=[];const vm=vmFor(async(_path,options)=>{calls++;bodies.push(options.body);return answer('unused');});
  vm.startFollowUp({owner:'1',body:{query:'问'.repeat(194)},scope:'原范围',mode:'agent'});
  assert.equal(vm.composerLimit,2);vm.draft='三个字';await vm.submit();
  assert.equal(calls,0);assert.equal(vm.draft,'三个字');assert.ok(vm.formError);
  vm.startFollowUp({owner:'1',body:{query:'问'.repeat(195),shopId:'10'},scope:'原范围',mode:'agent'});
  assert.equal(vm.followUp.editingOriginal,true);assert.equal(vm.draft.length,195);assert.equal(vm.composerLimit,200);
  vm.shopId='20';vm.draft='缩短后的原问题';await vm.submit();
  assert.equal(calls,1);assert.equal(bodies[0].query,'缩短后的原问题');assert.equal(bodies[0].shopId,'10');
});

test('shop card continuation uses a scoped read-only query and never dispatches an order',async()=>{
  const calls=[];const vm=vmFor(async(path,options)=>{calls.push({path,options});return answer('有券');});
  await vm.queryShopOffers({shopId:'9223372036854775807',title:'选中店铺',voucherId:''});
  assert.equal(calls.length,1);assert.equal(calls[0].path,'/agent/query');
  assert.equal(calls[0].options.body.shopId,'9223372036854775807');
  assert.equal(vm.messages[0].scope,'选中店铺');
});

test('many shop results cannot crowd every coupon out of the bounded card list',()=>{
  const cards=observationCards([
    {tool:'searchShops',result:{status:'OK',data:{items:Array.from({length:20},(_,i)=>({shopId:String(i+1),name:'店铺',avgPriceYuan:50}))}}},
    {tool:'listShopVouchers',result:{status:'OK',data:{items:Array.from({length:8},(_,i)=>({shopId:'1',voucherId:String(i+100),title:'优惠券',payCents:1000}))}}},
  ]);
  assert.equal(cards.length,20);assert.equal(cards.filter(c=>c.kind==='voucher').length,8);
});

test('unknown coupon prices are not rendered as free while verified zero remains free',()=>{
  const cards=observationCards([{tool:'listShopVouchers',result:{status:'OK',data:{items:[
    {shopId:'1',voucherId:'10',title:'未知价格',payCents:null},
    {shopId:'1',voucherId:'11',title:'免费券',payCents:0},
    {shopId:'1',voucherId:'12',title:'不合法价格',payCents:false},
    {shopId:'1',voucherId:'13',title:'空价格',payCents:' '},
  ]}}}]);
  assert.equal(cards[0].price,'');assert.equal(cards[1].price,'券价 ¥0');
  assert.equal(cards[2].price,'');assert.equal(cards[3].price,'');
});

test('long waiting indicator describes elapsed waiting rather than invented tool progress',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const pending=deferred(),vm=vmFor(()=>pending.promise);
  const run=vm.send({query:'找个餐厅'},'按需求查询','agent');
  assert.equal(vm.messages[0].waitingLonger,false);t.mock.timers.tick(8000);
  assert.equal(vm.messages[0].waitingLonger,true);pending.resolve(answer('查到了'));await run;
  assert.equal(vm.messages[0].pending,false);
});
