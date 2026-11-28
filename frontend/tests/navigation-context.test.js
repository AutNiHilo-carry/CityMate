import test from 'node:test';
import assert from 'node:assert/strict';
import {contextualHref, returnTarget} from '../scripts/api.js';
import {pageVm} from './page-harness.js';

test('new-tab detail links retain submitted filters and anchors without losing long IDs', () => {
  const source='/vouchers.html?keyword=%E7%81%AB%E9%94%85&type=1&shopId=12#offers';
  const href=contextualHref('/shop-detail.html?id=9223372036854775807',source,'http://localhost');
  const url=new URL(href,'http://localhost');
  assert.equal(url.searchParams.get('id'),'9223372036854775807');
  assert.equal(url.searchParams.get('from'),source);
  assert.equal(returnTarget(url.searchParams.get('from'),'/',url.origin),source);
});

test('return context rejects external, login, unsupported and excessive URLs', () => {
  for(const value of ['https://evil.example/shop-list.html','//evil.example/','javascript:alert(1)',
    '/login.html?return=/','/api/user/me','/missing.html','/shop-list.html?x='+'x'.repeat(1500)]) {
    assert.equal(returnTarget(value,'/shop-list.html','http://localhost'),'/shop-list.html');
  }
  assert.equal(contextualHref('https://evil.example/shop-detail.html','/','http://localhost'),'/');
});

test('detail fallback and login links keep the user in the original business context', () => {
  const vm=pageVm(async()=>({data:[]}),{pathname:'/shop-detail.html',search:'?id=1&from='+encodeURIComponent('/shop-list.html?type=1&maxPrice=50')});
  assert.equal(vm.detailBackHref,'/shop-list.html?type=1&maxPrice=50');
  assert.equal(vm.detailBackLabel,'返回搜索结果');
  assert.equal(new URL(vm.loginHref,'http://localhost').searchParams.get('return'),'/shop-detail.html?id=1&from='+encodeURIComponent('/shop-list.html?type=1&maxPrice=50'));
  vm.requireUser();assert.equal(vm.navigation[0],vm.loginHref);
  const direct=pageVm(async()=>({data:[]}),{pathname:'/shop-detail.html',search:'?id=1'});direct.shop={typeId:2};
  assert.equal(direct.detailBackHref,'/shop-list.html?type=2');
});
