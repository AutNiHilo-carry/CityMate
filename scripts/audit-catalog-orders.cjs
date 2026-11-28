const{chromium}=require('../target/ui-audit-tools/node_modules/playwright-core');
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const out=path.resolve('target/catalog-orders-checks');fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const checks=[],errors=[],writes=[];
 try{
  const context=await browser.newContext();
  await context.addInitScript(()=>{sessionStorage.setItem('token','catalog-orders-fixture');window.__copiedOrders=[];Object.defineProperty(navigator,'clipboard',{value:{writeText:async text=>window.__copiedOrders.push(text)}});});
  const shops=[{id:1,typeId:1,name:'运河火锅',avgPrice:50,score:48,area:'运河',images:'',comments:1,voucher:true},{id:2,typeId:1,name:'运河宴席',avgPrice:150,score:42,area:'运河',images:'',comments:1,voucher:false},{id:3,typeId:2,name:'西湖KTV',avgPrice:40,score:49,area:'西湖',images:'',comments:1,voucher:true}];
  const orders=[1,2,4].map((status,index)=>({order_id:['9007199254740993','9007199254740994','9007199254740995'][index],title:'业务测试订单',status,pay_cents:500,voucher_type:0,shop_id:1,created_at:'2026-11-11T12:00:00',rules:'领取时的原规则\\n不可叠加'}));
  const reply=(r,data,total)=>r.fulfill({json:{success:true,data,total}});
  await context.route('**/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()),p=url.pathname,q=url.searchParams;
   if(req.method()!=='GET'){writes.push(p);return route.abort();}
   if(p==='/api/user/me')return reply(route,{id:'991',nickName:'测试账号',icon:''});
   if(p==='/api/shop-type/list')return reply(route,[{id:1,name:'美食'},{id:2,name:'KTV'}]);
   if(p==='/api/shop/catalog'){
    let rows=shops.filter(s=>(!q.get('keyword')||s.name.includes(q.get('keyword')))&&(!q.get('typeId')||String(s.typeId)===q.get('typeId'))&&(!q.get('area')||s.area.includes(q.get('area')))&&(!q.get('maxPrice')||s.avgPrice<=Number(q.get('maxPrice')))&&(!q.get('minScore')||s.score>=Number(q.get('minScore')))&&(q.get('hasVoucher')!=='true'||s.voucher));
    return reply(route,rows,rows.length);
   }
   if(p==='/api/voucher-order/mine'||p==='/api/voucher-order/mine/vouchers'){
    const rows=p.endsWith('/vouchers')?orders.filter(o=>o.status===2):orders;
    return reply(route,rows,rows.length);
   }
   throw new Error('Unexpected API '+p);
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://127.0.0.1:8080/shop-list.html',{waitUntil:'networkidle'});
  await page.getByLabel('人均预算',{exact:true}).selectOption('50');
  await page.waitForFunction(()=>document.querySelectorAll('.shop-card').length===2);
  await page.getByLabel('评分',{exact:true}).selectOption('45');
  await page.getByLabel('商圈 / 地址',{exact:true}).fill('运河');
  await page.getByRole('button',{name:'查找店铺',exact:true}).click();
  await page.waitForFunction(()=>document.querySelectorAll('.shop-card').length===1);
  const couponResponse=page.waitForResponse(r=>r.url().includes('/api/shop/catalog')&&r.url().includes('hasVoucher=true'));
  await page.getByLabel('只看有优惠券的店铺',{exact:true}).check();
  await couponResponse;
  assert.equal(await page.locator('.shop-card h3').innerText(),'运河火锅');checks.push('budget score area and coupon filters combine');
  assert.equal(new URL(page.url()).searchParams.get('maxPrice'),'50');
  assert.equal(new URL(page.url()).searchParams.get('hasVoucher'),'true');checks.push('submitted filters are reflected in shareable URL');
  await page.reload({waitUntil:'networkidle'});
  assert.equal(await page.getByLabel('人均预算',{exact:true}).inputValue(),'50');
  assert.equal(await page.getByLabel('只看有优惠券的店铺',{exact:true}).isChecked(),true);checks.push('filters restore after reload');
  for(const width of [1440,390,320]){
   await page.setViewportSize({width,height:900});
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2));
   await page.screenshot({path:path.join(out,`shops-${width}.png`),fullPage:true});
  }
  await page.getByRole('button',{name:'清空筛选',exact:true}).click();
  await page.waitForFunction(()=>document.querySelectorAll('.shop-card').length===3);checks.push('reset restores complete catalog');
  await page.goto('http://127.0.0.1:8080/orders.html',{waitUntil:'networkidle'});
  assert.equal(await page.locator('.order-card').count(),3);
  await page.getByRole('button',{name:'已领取',exact:true}).click();
  await page.waitForFunction(()=>document.querySelectorAll('.order-card').length===1);checks.push('held-order tab queries server filtered list');
  await page.getByRole('button',{name:'复制订单号 9007199254740994',exact:true}).click();
  assert.deepEqual(await page.evaluate(()=>window.__copiedOrders),['9007199254740994']);checks.push('copy retains full order number without writing system clipboard');
  await page.locator('.order-rules summary').click();
  assert.equal(await page.locator('.order-rules p').innerText(),'领取时的原规则\n不可叠加');checks.push('order rules show the saved snapshot');
  for(const width of [1440,390,320]){
   await page.setViewportSize({width,height:900});
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2));
   await page.screenshot({path:path.join(out,`orders-${width}.png`),fullPage:true});
  }
  await page.reload({waitUntil:'networkidle'});
  assert.equal(await page.locator('.order-card').count(),1);checks.push('held-order tab restores from URL');
  await page.getByRole('button',{name:'全部订单',exact:true}).click();
  await page.waitForFunction(()=>document.querySelectorAll('.order-card').length===3);
  assert.equal(await page.getByRole('button',{name:'取消订单',exact:true}).count(),1);checks.push('cancel action appears only for an unpaid ordinary order');
  assert.equal(errors.length,0);assert.equal(writes.length,0);
  const report={checks,pageErrors:errors,writeAttempts:writes,apiFixtures:true,systemClipboardChanged:false,realBusinessWrites:0,failures:0};
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({checks:checks.length,pageErrors:errors.length,realBusinessWrites:0,failures:0}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
