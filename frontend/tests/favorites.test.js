import test from 'node:test';
import assert from 'node:assert/strict';
import {favoriteButton,favoriteList} from '../scripts/favorites.js';
import {deferred} from './page-harness.js';
function vmFor(component,api){const vm={...component.data(),user:{id:'7'},userReady:true,id:'10',kind:'shop',api};for(const [key,method]of Object.entries(component.methods))vm[key]=method.bind(vm);return vm;}

test('favorite writes wait for confirmation and double clicks send only one explicit desired state',async()=>{
 const pending=deferred(),calls=[];const vm=vmFor(favoriteButton,async(path,opts)=>{calls.push(opts);return pending.promise;});
 vm.known=true;const first=vm.toggle();await vm.toggle();assert.equal(calls.length,1);assert.equal(vm.saved,false);
 assert.deepEqual(calls[0].body,{saved:true});pending.resolve({data:true});await first;assert.equal(vm.saved,true);
});
test('uncertain favorite writes require a read before further mutation',async()=>{
 const calls=[];const vm=vmFor(favoriteButton,async(_path,opts)=>{calls.push(opts);if(opts.method)throw new Error('timeout');return {data:true};});
 vm.known=true;await vm.toggle();assert.equal(vm.known,false);await vm.toggle();assert.equal(calls.length,1);
 await vm.load();assert.equal(vm.saved,true);assert.equal(vm.known,true);assert.equal(calls.length,2);
});
test('switching users drops saved state and rejects the old completion',async()=>{
 const pending=deferred();const vm=vmFor(favoriteButton,()=>pending.promise);vm.known=true;
 const save=vm.toggle();vm.user={id:'8'};vm.api=async()=>({data:false});await vm.load();
 pending.resolve({data:true});await save;assert.equal(vm.saved,false);assert.equal(vm.busy,false);
});
test('failed collection pagination retains rows and retries the same page',async()=>{
 const calls=[];let fail=true;const vm=vmFor(favoriteList,async(_path,opts)=>{calls.push(opts.params.page);if(fail)throw new Error('offline');return {data:[{id:'2'}],total:2};});
 vm.items=[{id:'1'}];vm.page=2;await vm.load();assert.equal(vm.page,2);assert.equal(vm.items.length,1);
 fail=false;await vm.load();assert.deepEqual(calls,[2,2]);assert.equal(vm.items.length,2);
});
test('confirmed removal refreshes page one instead of skipping rows shifted by deletion',async()=>{
 const calls=[];const vm=vmFor(favoriteList,async(path,opts)=>{calls.push(opts);return opts.method?{data:false}:{data:[{id:'2'}],total:1};});
 vm.items=[{id:'1'}];vm.page=4;await vm.remove(vm.items[0]);assert.equal(calls[1].params.page,1);assert.equal(vm.items[0].id,'2');
});
test('user switch during collection removal clears private rows and ignores stale replies',async()=>{
 const pending=deferred();const vm=vmFor(favoriteList,()=>pending.promise);vm.items=[{id:'1',title:'private'}];
 const removal=vm.remove(vm.items[0]);vm.user={id:'8'};vm.api=async()=>({data:[],total:0});favoriteList.watch.user.call(vm);
 await Promise.resolve();pending.resolve({data:false});await removal;assert.deepEqual(vm.items,[]);
});
