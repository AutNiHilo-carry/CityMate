const {chromium}=require('../target/ui-audit-tools/node_modules/playwright-core');
const fs=require('fs');
const path=require('path');
const assert=require('assert/strict');
(async()=>{
 const out=path.resolve('target/ui-audit-final');fs.mkdirSync(out,{recursive:true});
 let ready=false;
 for(let n=0;n<40;n++){
  try{const r=await fetch('http://127.0.0.1:8081/actuator/health');if((await r.json()).status==='UP'){ready=true;break;}}catch{}
  await new Promise(resolve=>setTimeout(resolve,2000));
 }
 assert.ok(ready,'Java health must be UP before page acceptance');
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const checks=[],pages=[],blocked=[];
 const fulfill=(route,data,total)=>route.fulfill({json:{success:true,data,total}});
 async function context(auth){
  const c=await browser.newContext();
  if(auth) await c.addInitScript(()=>sessionStorage.setItem('token','ui-fixture-not-a-real-token'));
  await c.route('**/api/**',async route=>{
   const req=route.request(),p=new URL(req.url()).pathname;
   if(req.method()!=='GET'){blocked.push(p);return route.abort();}
   if(auth){
    if(p==='/api/user/me')return fulfill(route,{id:'999999',nickName:'布局测试账号',icon:''});
    if(p==='/api/user/sign/count')return fulfill(route,0);
    if(p==='/api/blog/of/me')return fulfill(route,[]);
    if(p.startsWith('/api/follow/or/not/'))return fulfill(route,false);
    if(p==='/api/voucher-order/mine')return fulfill(route,[{order_id:'9007199254740993',title:'测试夹具：店铺代金券',pay_cents:4750,status:1,voucher_type:0,shop_id:'10',created_at:'2026-11-11T12:00:00'}],1);
   }
   return route.continue();
  });return c;
 }
 try{
  for(const auth of [false,true]){
   const c=await context(auth);
   const routes=auth?[['profile','/info.html'],['orders','/orders.html'],['publish','/blog-edit.html']]:[['home','/'],['shops','/shop-list.html'],['vouchers','/vouchers.html'],['shop','/shop-detail.html?id=10'],['note','/blog-detail.html?id=1'],['login','/login.html'],['assistant','/assistant.html'],['experience','/experience.html'],['other-profile','/other-info.html?id=1']];
   for(const width of [1440,1024,390,320])for(const [name,url]of routes){
    const p=await c.newPage();await p.setViewportSize({width,height:900});
    const errors=[];p.on('pageerror',e=>errors.push(e.message));
    p.on('response',r=>{if(r.url().includes('/api/')&&r.status()>=400&&r.status()!==401)errors.push(`API ${r.status()} ${new URL(r.url()).pathname}`);});
    await p.goto('http://127.0.0.1:8080'+url,{waitUntil:'networkidle',timeout:45000});
    await p.waitForTimeout(150);
    const scroll=await p.evaluate(()=>document.documentElement.scrollWidth);
    await p.screenshot({path:path.join(out,`${name}-${width}.png`),fullPage:true});
    pages.push({name,width,scroll,authFixture:auth,errors});
    assert.equal(errors.length,0,`${name} ${width} script error`);
    assert.ok(scroll<=width+2,`${name} ${width} overflow ${scroll}`);
    await p.close();
   }
   await c.close();
  }
  const c=await context(false),p=await c.newPage();
  await p.goto('http://127.0.0.1:8080/vouchers.html',{waitUntil:'networkidle'});
  await p.locator('.offer-search input').fill('NO_MATCH_UI_AUDIT_20261111');
  let response=p.waitForResponse(r=>r.url().includes('/api/voucher/catalog?'));
  await p.getByRole('button',{name:'搜索优惠',exact:true}).click();await response;
  await p.getByRole('heading',{name:'没有找到符合条件的优惠券'}).waitFor();checks.push('live empty keyword state');
  response=p.waitForResponse(r=>r.url().includes('/api/voucher/catalog?'));
  await p.getByRole('button',{name:'清空筛选',exact:true}).click();await response;
  await p.locator('.voucher-card').first().waitFor();checks.push('live reset restores results');
  response=p.waitForResponse(r=>r.url().includes('/api/voucher/catalog?'));
  await p.getByRole('button',{name:'KTV',exact:true}).click();
  const data=await(await response).json();
  await p.waitForFunction(n=>document.querySelectorAll('.voucher-card').length===n,data.data.length);checks.push('live category response matches rendered cards');
  await p.goto('http://127.0.0.1:8080/',{waitUntil:'networkidle'});
  await p.locator('.home-shortcuts a[href="/blog-edit.html"]').click();
  await p.waitForURL('**/login.html?return=**');checks.push('write-note shortcut preserves login return');
  await c.close();
  const auth=await context(true),claim=await auth.newPage();
  let reads=0,stubbedPosts=0;
  await auth.route('**/api/voucher/preview?**',async route=>{
   const q=new URL(route.request().url()).searchParams;
   reads++;
   return fulfill(route,{shopId:q.get('shopId'),voucherId:q.get('voucherId'),type:0,title:'自动化确认流程（桩）',rules:'仅用于无写入界面检查',payCents:reads>1?600:500,valueCents:1000,availableAtQuery:true});
  });
  await auth.route('**/api/voucher-order/ordinary/*',async route=>{stubbedPosts++;return fulfill(route,'42');});
  await claim.goto('http://127.0.0.1:8080/vouchers.html',{waitUntil:'networkidle'});
  await claim.locator('.voucher-card:not(.voucher-unavailable) .button').first().click();
  await claim.getByRole('dialog').waitFor();
  await claim.locator('.dialog .button.primary').click();
  await claim.waitForFunction(()=>document.querySelector('.dialog-price')?.textContent.includes('6'));
  assert.equal(stubbedPosts,0);checks.push('changed-price dialog requires another confirmation (mock)');
  await claim.locator('.dialog .button.primary').evaluate(e=>{e.click();e.click();});
  await claim.waitForURL('**/orders.html');
  assert.equal(stubbedPosts,1);checks.push('confirmation dispatches once (mock)');
  await auth.close();
  const report={pages,checks,realBusinessWrites:0,blockedWrites:blocked,stubbedPosts,browser:'isolated headless Edge',authScreenshots:'mock user/order data',failures:0};
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({pageViewportChecks:pages.length,interactionChecks:checks.length,stubbedPosts,blockedWrites:blocked,failures:0}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
