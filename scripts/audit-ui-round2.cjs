const {chromium}=require('../target/ui-audit-tools/node_modules/playwright-core');
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const out=path.resolve('target/ui-audit-round2');fs.mkdirSync(out,{recursive:true});
 const base=process.env.CITYMATE_AUDIT_URL || 'http://127.0.0.1:8080';
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const checks=[],errors=[],blocked=[],stubbed=[];
 try{
  const context=await browser.newContext();
  await context.addInitScript(()=>sessionStorage.setItem('token','round2-fixture'));
  let releaseHot,hotArrived,delayHot=true,hotCalls=0,failPage=true,failVouchers=false,signReads=0,recoverStreak=false;
  const hotReady=new Promise(r=>hotArrived=r);
  const delayed=new Promise(r=>releaseHot=r);
  const blog=id=>({id,userId:991,shopId:10,name:'测试作者',title:id==='old'?'旧列表笔记':id==='follow'?'关注作者的新笔记':'<img src=x onerror="window.__xss=1">',images:'/assets/image-placeholder.svg',liked:0});
  const ok=(r,data,total)=>r.fulfill({json:{success:true,data,total}});
  await context.route('**/api/**',async r=>{
   const req=r.request(),url=new URL(req.url()),p=url.pathname;
   if(req.method()!=='GET'){
    if(p==='/api/voucher-order/42/cancel'){stubbed.push(p);return r.fulfill({status:504,json:{success:false}});}
    if(p==='/api/user/sign'){recoverStreak=false;stubbed.push(p);return ok(r,null);}
    blocked.push(p);return r.abort();
   }
   if(p==='/api/user/me')return ok(r,{id:'991',nickName:'测试用户'});
   if(p==='/api/user/sign/count')return recoverStreak?ok(r,3):++signReads===1?ok(r,0):r.fulfill({status:503,json:{success:false}});
   if(p==='/api/blog/of/me')return ok(r,[]);
   if(p==='/api/shop-type/list')return ok(r,[{id:1,name:'美食'}]);
   if(p==='/api/shop/catalog')return ok(r,[],0);
   if(p==='/api/blog/hot'){
    if(delayHot){hotArrived();await delayed;return ok(r,[blog('old')]);}
    hotCalls++;
    if(url.searchParams.get('current')==='2'&&failPage)return r.fulfill({status:503,json:{success:false}});
    return ok(r,hotCalls===1?[blog('new')]:[blog('new'),blog('next')]);
   }
   if(p==='/api/blog/of/follow')return ok(r,{list:[blog('follow')],minTime:20,offset:1});
   if(p==='/api/shop/10')return ok(r,{id:10,name:'测试餐厅',images:'/assets/image-placeholder.svg',score:48,avgPrice:50});
   if(p.startsWith('/api/voucher/list/'))return failVouchers?r.fulfill({status:503,json:{success:false}}):ok(r,[]);
   if(p==='/api/blog/of/shop')return ok(r,[blog('new')],1);
   if(p==='/api/voucher-order/mine')return ok(r,[{order_id:'42',title:'测试代金券',status:1,voucher_type:0,pay_cents:500,shop_id:10}],1);
   throw new Error('Unexpected fixture API '+p);
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base+'/',{waitUntil:'domcontentloaded'});await hotReady;
  await page.getByRole('button',{name:'关注动态',exact:true}).click();
  await page.locator('.blog-card-title').filter({hasText:'关注作者的新笔记'}).waitFor();
  releaseHot();await page.waitForLoadState('networkidle');
  assert.equal(await page.locator('.blog-card').count(),1);
  assert.equal(await page.locator('.blog-card-title').innerText(),'关注作者的新笔记');
  checks.push('delayed hot response never contaminates the selected follow feed');
  delayHot=false;await page.getByRole('button',{name:'热门推荐',exact:true}).click();
  await page.locator('.blog-card-title').filter({hasText:'onerror'}).waitFor();
  assert.equal(await page.evaluate(()=>window.__xss),undefined);
  checks.push('untrusted note titles render as text rather than HTML');
  await page.getByRole('button',{name:'再发现一些'}).click();
  await page.locator('.feed-section .error-state').waitFor();
  assert.equal(await page.locator('.blog-card').count(),1);
  failPage=false;await page.locator('.feed-section .error-state').getByRole('button',{name:'重新加载'}).click();
  await page.waitForFunction(()=>document.querySelectorAll('.blog-card').length===2);
  assert.equal(await page.locator('.feed-section .error-state').count(),0);
  checks.push('failed pagination retains cards and retry clears errors without duplicates');
  await page.setViewportSize({width:320,height:800});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2));
  await page.screenshot({path:path.join(out,'fault-feed-320.png'),fullPage:true});
  checks.push('long untrusted titles fit narrow cards');
  failVouchers=true;await page.goto(base+'/shop-detail.html?id=10',{waitUntil:'networkidle'});
  await page.getByRole('heading',{name:'测试餐厅',exact:true}).waitFor();
  await page.getByRole('button',{name:'重试优惠查询'}).waitFor();
  assert.equal(await page.locator('.detail-main .blog-card').count(),1);
  checks.push('voucher outage does not hide shop details or notes');
  failVouchers=false;await page.getByRole('button',{name:'重试优惠查询'}).click();
  await page.getByText('这家店铺暂时没有上架优惠券。',{exact:true}).waitFor();
  checks.push('shop voucher retry recovers into an honest empty state');
  await page.goto(base+'/orders.html',{waitUntil:'networkidle'});
  await page.getByRole('button',{name:'取消订单',exact:true}).click();
  await page.locator('.dialog').waitFor();
  assert.equal(await page.locator('main').evaluate(e=>e.inert),true);
  await page.keyboard.press('Shift+Tab');
  assert.equal(await page.locator('.dialog').getByRole('button',{name:'确认取消',exact:true}).evaluate(e=>document.activeElement===e),true);
  await page.keyboard.press('Escape');await page.locator('.dialog').waitFor({state:'hidden'});
  assert.equal(await page.locator('main').evaluate(e=>e.inert),false);
  assert.equal(await page.getByRole('button',{name:'取消订单',exact:true}).evaluate(e=>document.activeElement===e),true);
  checks.push('confirmation dialog locks background and restores focus after escape');
  await page.getByRole('button',{name:'取消订单',exact:true}).click();
  await page.locator('.dialog').getByRole('button',{name:'确认取消',exact:true}).click();
  await page.getByRole('status').filter({hasText:'取消结果未确认'}).waitFor();
  assert.equal(stubbed.filter(p=>p.endsWith('/cancel')).length,1);
  assert.equal(await page.locator('.order-state').innerText(),'待付款');
  checks.push('timeout cancellation stays uncertain and never retries its POST');
  signReads=1;await page.goto(base+'/info.html',{waitUntil:'networkidle'});
  await page.getByRole('button',{name:'重新查询',exact:true}).waitFor();
  await page.getByRole('heading',{name:'这里还没有探店记录',exact:true}).waitFor();
  checks.push('streak outage does not prevent profile notes from loading');
  recoverStreak=true;await page.getByRole('button',{name:'重新查询',exact:true}).click();
  await page.getByText('连续签到 3 天',{exact:true}).waitFor();
  checks.push('streak retry updates its own section without a page reload');
  await page.getByRole('button',{name:'今日签到',exact:true}).click();
  await page.getByRole('button',{name:'今天已签到',exact:true}).waitFor();
  assert.equal(await page.getByRole('button',{name:'今天已签到',exact:true}).isDisabled(),true);
  await page.getByRole('status').filter({hasText:'签到成功，连续天数暂未更新'}).waitFor();
  checks.push('sign-in success survives an independent streak-read outage');
  assert.deepEqual(errors,[]);assert.deepEqual(blocked,[]);
  fs.writeFileSync(path.join(out,'fault-results.json'),JSON.stringify({checkedAt:new Date().toISOString(),checks,pageErrors:errors,blockedWrites:blocked,stubbedWrites:stubbed,realBusinessWrites:0,failures:0},null,2));
  console.log(JSON.stringify({checks:checks.length,failures:0,realBusinessWrites:0}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
