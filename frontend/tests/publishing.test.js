import test from "node:test";
import assert from "node:assert/strict";
import { pageVm, memoryStorage } from "./page-harness.js";
import { noteDraftKey, readNoteDraft, encodeNoteDraft, cleanNoteDraft } from "../scripts/note-draft.js";

const draft = { title: "晚饭吃了火锅", content: "菜品与服务的真实体验", shopId: "99",
  pictures: ["/blogs/1/2/abc.jpg", "/blogs/1/2/def.png"] };
function publishingVm(api = async()=>({data:[]} ), storage = memoryStorage()) {
  const vm=pageVm(api,{storage,pathname:"/blog-edit.html"});
  vm.user={id:"9007199254740993",nickName:"author"};
  vm.draftStorageReady=true;
  vm.draft={...draft,pictures:[...draft.pictures]};
  vm.publishShops=[{id:"99",name:"shop"}];
  return vm;
}

test("local draft round trips preserve exact owner IDs and cover ordering",()=>{
  assert.notEqual(noteDraftKey("9007199254740993"),noteDraftKey("9007199254740992"));
  const value=readNoteDraft(encodeNoteDraft(draft,true));
  assert.deepEqual(value.draft,draft);
  assert.equal(value.uncertain,true);
});

test("malformed drafts and unsafe or excessive photo paths cannot be restored",()=>{
  assert.equal(readNoteDraft("broken"),null);
  assert.equal(readNoteDraft(JSON.stringify({version:2,draft})),null);
  assert.equal(cleanNoteDraft({...draft,pictures:["javascript:alert(1)"]}),null);
  assert.equal(cleanNoteDraft({...draft,pictures:["/blogs/../private.jpg"]}),null);
  assert.equal(cleanNoteDraft({...draft,content:"x".repeat(10001)}),null);
  assert.equal(cleanNoteDraft({...draft,pictures:Array(10).fill(draft.pictures[0])}),null);
});

test("restoration is an explicit choice and a previous shop outside search page is retained",async()=>{
  const storage=memoryStorage();storage.setItem(noteDraftKey("9007199254740993"),encodeNoteDraft(draft));
  const vm=publishingVm(async path=>({data:path.startsWith("/shop/catalog")?[{id:"1",name:"other"}]:{id:"99",name:"original"}}),storage);
  vm.draft={title:"",content:"",shopId:"1",pictures:[]};
  vm.loadLocalDraft();
  assert.equal(vm.draftStorageReady,false);
  assert.equal(vm.draft.shopId,"1");
  await vm.restoreLocalDraft();
  assert.equal(vm.draft.title,draft.title);
  assert.equal(vm.draft.shopId,"99");
  assert.equal(vm.publishShops[0].id,"99");
  assert.equal(vm.draftStorageReady,true);
});

test("one user cannot load another user's browser draft",()=>{
  const vm=publishingVm();vm.persistDraft();
  vm.user={id:"2"};vm.draftRecovery=null;
  vm.loadLocalDraft();
  assert.equal(vm.draftRecovery,null);
});

test("storage failures keep the editable draft and expose save failure",()=>{
  const vm=publishingVm(undefined,{getItem(){return null;},setItem(){throw new Error("quota");},removeItem(){}});
  assert.equal(vm.persistDraft(),false);
  assert.equal(vm.draftSaveState,"unavailable");
  assert.equal(vm.draft.content,draft.content);
});

test("changing cover only reorders photo paths and never dispatches a write",()=>{
  let calls=0;const vm=publishingVm(async()=>{calls++;return {data:[]};});
  vm.setNoteCover(draft.pictures[1]);
  assert.equal(vm.draft.pictures[0],draft.pictures[1]);
  assert.equal(vm.draft.pictures.length,2);
  assert.equal(calls,0);
});

test("uncertain publication persists the warning and blocks automatic resubmission",async()=>{
  let calls=0;const vm=publishingVm(async()=>{calls++;throw new Error("timeout");});
  await vm.saveBlog();await vm.saveBlog();
  assert.equal(calls,1);
  assert.equal(vm.publishUncertain,true);
  assert.equal(readNoteDraft(vm.browserStorage.getItem(noteDraftKey(vm.user.id))).uncertain,true);
  assert.equal(vm.navigation.length,0);
});

test("successful publication clears only the current owner's local draft",async()=>{
  let payload;const vm=publishingVm(async(_,options)=>{payload=options.body;return {data:"9007199254740995"};});
  vm.browserStorage.setItem(noteDraftKey("2"),encodeNoteDraft(draft));
  vm.persistDraft();await vm.saveBlog();
  assert.equal(payload.images,draft.pictures.join(","));
  assert.equal(vm.browserStorage.getItem(noteDraftKey(vm.user.id)),null);
  assert.ok(vm.browserStorage.getItem(noteDraftKey("2")));
  assert.equal(vm.draftSaved,true);
  assert.equal(vm.navigation[0],"/blog-detail.html?id=9007199254740995");
});

test("shop notes append another page and keep the failed page retryable",async()=>{
  let fail=false;const pages=[];
  const vm=pageVm(async(_,options)=>{
    pages.push(options.params.current);
    if(fail)throw new Error("offline");
    return {data:[{id:String(options.params.current)}],total:6};
  });
  vm.shop={id:"1"};await vm.loadShopBlogs(true);
  fail=true;await vm.loadShopBlogs();
  assert.equal(vm.shopBlogsPage,2);
  assert.equal(vm.shopBlogsError,"offline");
  fail=false;await vm.loadShopBlogs();
  assert.deepEqual(pages,[1,2,2]);
  assert.equal(vm.shopBlogs.length,2);
});

test("a shop voucher failure is distinct from an empty voucher list",async()=>{
  let fail=true;const vm=pageVm(async()=>{if(fail)throw new Error("offline");return {data:[]};});
  vm.shop={id:"1"};await vm.loadShopVouchers();
  assert.equal(vm.shopVouchersError,"offline");
  fail=false;await vm.loadShopVouchers();
  assert.equal(vm.shopVouchersError,"");
  assert.equal(vm.shopVouchersLoading,false);
});
