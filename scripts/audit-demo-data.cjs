// Real local catalog reads; no model, login, claim or payment requests.
const {chromium}=require('../target/ui-audit-tools/node_modules/playwright-core');
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const base='http://127.0.0.1:8080',out=path.resolve('target/demo-data-checks');fs.mkdirSync(out,{recursive:true});
 const checks=[],pages=[],errors=[];
 async function api(route){const response=await fetch(base+route);assert.equal(response.status,200);const body=await response.json();assert.equal(body.success,true);return body.data;}
 for(let type=1;type<=10;type++){
  const cover=await fetch(base+'/assets/demo/category-'+type+'.svg');assert.equal(cover.status,200);assert.ok((await cover.text()).includes('<svg'));
  const shops=await api('/api/shop/catalog?keyword='+encodeURIComponent('【演示】')+'&typeId='+type+'&size=20');
  assert.ok(shops.length>=2);assert.ok(shops.every(s=>s.typeId===type&&s.name.startsWith('【演示】')));
  const vouchers=await api('/api/voucher/catalog?keyword='+encodeURIComponent('【演示】')+'&typeId='+type+'&size=20');
  assert.ok(vouchers.length>=2);checks.push('category '+type+' contains synthetic shops and coupons');
 }
 const vouchers=await api('/api/voucher/list/880001');assert.ok(vouchers.some(v=>v.payValue===0));assert.ok(vouchers.some(v=>v.payValue>0));
 assert.ok(vouchers.every(v=>v.type===0&&v.rules.includes('演示')));checks.push('free and paid ordinary coupons have explicit synthetic rules');
 const notes=await api('/api/blog/of/shop?shopId=880001');assert.ok(notes.some(n=>n.title.includes('演示')));checks.push('store note is available without fabricating engagement');
 let browser;
 try{
  browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
  const context=await browser.newContext();
  await context.route('**/api/**',r=>r.request().method()==='GET'?r.continue():r.abort());
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  for(const width of [1440,390])for(const [name,route] of [
   ['shops','/shop-list.html?keyword='+encodeURIComponent('【演示】')],
   ['coupons','/vouchers.html?keyword='+encodeURIComponent('【演示】')],
   ['detail','/shop-detail.html?id=880001']]){
    await page.setViewportSize({width,height:900});await page.goto(base+route,{waitUntil:'networkidle'});
    await page.locator('h1,h2,h3').filter({hasText:'【演示】'}).first().waitFor();
    if(name==='coupons'){
     const rules=page.locator('.voucher-key-rules').first();await rules.waitFor();
     assert.ok((await rules.textContent()).includes('规则原文摘录'));
     assert.ok((await rules.textContent()).includes('待确认'));
     assert.ok((await rules.textContent()).includes('消费满60元抵10元'));
    }
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2));
    if(name!=='coupons'){
     const covers=page.locator('img[src*="/assets/demo/"]');assert.ok(await covers.count()>0);
     await page.waitForFunction(()=>{const img=document.querySelector('img[src*="/assets/demo/"]');return img&&img.complete&&img.naturalWidth>0;});
    }
    await page.screenshot({path:path.join(out,name+'-'+width+'.png'),fullPage:true});pages.push({name,width});
  }
  assert.deepEqual(errors,[]);
  const report={checkedAt:new Date().toISOString(),checks,pages,pageErrors:errors,failures:0,realModelCalls:0,ordersCreated:0};
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({checks:checks.length,pageViewportChecks:pages.length,failures:0}));
 }finally{if(browser)await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
