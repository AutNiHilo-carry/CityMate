import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";

const components={};
runInNewContext(readFileSync(new URL("../scripts/components.js",import.meta.url),"utf8")
  .replace(/^import .*;\r?\n/gm,""), {
  Vue:{component(name, options){components[name]=options;}},
  imagePath(){}, images(){}, money(){},
});
const card=components["voucher-card"];

test("saving labels use valid cent amounts and do not invent discounts",()=>{
  const saving=voucher=>card.computed.savingCents.call({voucher});
  assert.equal(saving({payValue:4500,actualValue:5000}),500);
  assert.equal(saving({payValue:'4750',actualValue:'5000'}),250);
  for(const voucher of [{payValue:0,actualValue:5000},{payValue:null,actualValue:5000},
    {payValue:5000,actualValue:4500},{payValue:5000},{payValue:-1,actualValue:5000},
    {payValue:12.5,actualValue:5000},{payValue:5000,actualValue:'bad'}])assert.equal(saving(voucher),0);
});

test("voucher buttons use authoritative server availability rather than the browser clock", () => {
  assert.equal(card.computed.available.call({voucher:{type:1,status:1,orderable:true,
    stock:10,beginTime:"2999-01-01",endTime:"2999-01-02"}}),true);
  assert.equal(card.computed.available.call({voucher:{type:0,status:1,orderable:false}}),false);
});

test("missing availability and unknown voucher types fail closed", () => {
  for(const voucher of [{type:0},{type:2,orderable:true},{orderable:true},
    {type:0,status:0,orderable:true}]) {
    assert.equal(card.computed.available.call({voucher}),false);
  }
  assert.equal(card.computed.available.call({voucher:{type:"0",status:"1",orderable:true}}),true);
  assert.equal(card.computed.label.call({voucher:{type:"0",payValue:0},available:true}),"立即领取");
});

test("voucher rules render legacy newline escapes as plain text",()=>{
  assert.equal(card.methods.rulesText("堂食使用\\n无需预约"),"堂食使用\n无需预约");
  assert.equal(card.methods.rulesText("<script>text</script>"),"<script>text</script>");
});
