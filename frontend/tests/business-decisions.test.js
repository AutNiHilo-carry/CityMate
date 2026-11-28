import test from 'node:test';
import assert from 'node:assert/strict';
import {voucherRuleHighlights, voucherRuleSummary} from '../scripts/voucher-rules.js';
import {shopComparison} from '../scripts/assistant-helpers.js';

test('rule excerpts preserve negation, exceptions and thresholds without inventing eligibility', () => {
  const rules='消费满60元抵10元。无需预约，但周末需提前一天预约；不与其他优惠叠加。';
  const fields=voucherRuleHighlights(rules);
  assert.equal(fields[0].text,'消费满60元抵10元');
  assert.equal(fields[2].text,'无需预约，但周末需提前一天预约');
  assert.equal(fields[3].text,'不与其他优惠叠加');
  assert.equal(voucherRuleHighlights('请咨询店铺')[0].known,false);
  const named=voucherRuleHighlights('适用门店：周末桌游屋。周一至周日10:00—22:00使用。');
  assert.equal(named[1].text,'周一至周日10:00—22:00使用');
});

test('missing terms stay unknown, exact repeated excerpts are grouped and markup stays text', () => {
  const summary=voucherRuleSummary('周末需要预约\\n<script>不支持叠加</script>');
  assert.equal(summary.excerpts.filter(e=>e.text==='周末需要预约').length,1);
  assert.deepEqual(summary.excerpts[0].labels,['适用时间','预约要求']);
  assert.deepEqual(summary.missing,['使用门槛']);
  assert.equal(summary.excerpts[1].text,'<script>不支持叠加</script>');
  assert.equal(voucherRuleSummary(null).missing.length,4);
});

test('comparison keeps source order, bounds candidates and only joins coupons of the same shop', () => {
  const cards=[1,2,3,4].map(id=>({key:'s'+id,kind:'shop',shopId:String(id),title:'店'+id,price:id===1?'人均约 ¥50':''}));
  cards.push({key:'v1',kind:'voucher',shopId:'1'},{key:'v2',kind:'voucher',shopId:'99'});
  const compared=shopComparison(cards);
  assert.deepEqual(compared.map(s=>s.shopId),['1','2','3']);
  assert.equal(compared[0].couponSummary,'本次查到 1 张券，领取前需核对');
  assert.equal(compared[1].couponSummary,'未查询到券信息，可继续查券');
  assert.equal(compared[1].price,'');assert.equal(cards.length,6);
  assert.deepEqual(shopComparison(null),[]);
});
