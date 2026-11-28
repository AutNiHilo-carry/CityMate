// Scripted model/tool responses validate UI flow; no real model or business writes.
const {chromium}=require('../target/ui-audit-tools/node_modules/playwright-core');
const http=require('http'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const root=path.resolve('frontend'),out=path.resolve('target/ai-journey-checks');fs.mkdirSync(out,{recursive:true});
 const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml','.woff':'font/woff','.woff2':'font/woff2'};
 const server=http.createServer((req,res)=>{
  let file;
  try{file=path.resolve(root,'.'+decodeURIComponent(new URL(req.url,'http://localhost').pathname));}catch{res.writeHead(400);return res.end();}
  if(!file.startsWith(root+path.sep)&&file!==root){res.writeHead(403);return res.end();}
  if(file===root||file.endsWith('.html'))file=path.join(root,'index.html');
  fs.readFile(file,(err,data)=>{res.writeHead(err?404:200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream'});res.end(err?'':data);});
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const base='http://127.0.0.1:'+server.address().port;
 const shops=Array.from({length:5},(_,i)=>({shopId:String(i+1),name:['运河茶餐厅','河畔小馆','街角餐厅','好味茶社','午后小馆'][i],avgPriceYuan:50+i*10,area:'运河商圈'}));
 const vouchers=[{shopId:'1',voucherId:'101',title:'午市代金券',payCents:4500},{shopId:'1',voucherId:'102',title:'价格待核对的券'}];
 const observations=(includeShops=true)=>[...(includeShops?[{tool:'searchShops',result:{status:'OK',data:{items:shops}}}]:[]),{tool:'listShopVouchers',result:{status:'OK',data:{items:vouchers}}}];
 const answer=(all=true)=>({status:'ANSWERED',answered:true,answer:'运河茶餐厅人均约50元。查到一张45元的午市代金券，使用前请确认规则。',observations:observations(all),sources:[{sourceKey:'platform-faq',version:4,title:'优惠券使用说明'}],steps:[{tool:'searchShops',status:'OK'},{tool:'listShopVouchers',status:'OK'}]});
 let browser,releaseSlow;const checks=[],errors=[],queries=[],writes=[];let failNext=false;
 try{
  browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
  const context=await browser.newContext();await context.addInitScript(()=>sessionStorage.setItem('token','ai-journey-fixture'));
  const reply=(r,data)=>r.fulfill({json:{success:true,data}});
  await context.route('**/api/**',async r=>{
   const req=r.request(),p=new URL(req.url()).pathname;
   if(p==='/api/agent/query'||p==='/api/knowledge/answer'){
    const body=req.postDataJSON();queries.push({path:p,body});
    if(failNext){failNext=false;return r.fulfill({status:503,json:{success:false}});}
    if(body.query==='慢查询测试')return new Promise(resolve=>{releaseSlow=()=>reply(r,answer()).then(resolve);});
    if(body.query==='帮我找午餐')return reply(r,{status:'NEEDS_CLARIFICATION',answered:false,answer:'你想在哪个商圈用餐？',observations:[],sources:[]});
    return reply(r,answer(!body.shopId));
   }
   if(req.method()!=='GET'){writes.push(p);return r.abort();}
   if(p==='/api/user/me')return reply(r,{id:'1',nickName:'测试用户'});
   if(p==='/api/shop/catalog')return reply(r,shops.map(s=>({id:s.shopId,name:s.name})));
   if(p==='/api/assistant/claim-preview')return reply(r,{shopId:'1',voucherId:'101',type:0,title:'午市代金券',rules:'工作日午市使用，不与其他优惠叠加。',payCents:4500,valueCents:5000,availableAtQuery:true});
   if(p==='/api/knowledge/sources/platform-faq')return reply(r,{content:'使用前请确认适用日期和店铺。<script>window.__sourceScript=1</script>'});
   throw new Error('Unexpected API '+p);
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base+'/assistant.html',{waitUntil:'networkidle'});
  await page.getByRole('heading',{name:'小橙 AI 助手',exact:true}).waitFor();
  assert.equal(await page.locator('.ai-mascot-intro img').evaluate(img=>img.complete&&img.naturalWidth>0),true);
  await page.screenshot({path:path.join(out,'assistant-welcome-desktop.png'),fullPage:true});
  checks.push('named AI companion and original mascot load successfully');
  assert.equal(await page.locator('.ai-context-controls').evaluate(e=>e.open),false);
  checks.push('free-form finding starts without compulsory shop selection');
  const input=page.getByRole('textbox',{name:'向 CityMate AI 提问'});
  await input.fill('帮我找午餐');await page.getByRole('button',{name:'发送问题',exact:true}).click();
  await page.getByText('你想在哪个商圈用餐？',{exact:true}).waitFor();
  await page.getByRole('button',{name:'补充问题条件',exact:true}).click();
  await page.locator('.ai-followup-banner').waitFor();
  await input.fill('运河商圈，人均100元以内');await page.getByRole('button',{name:'发送补充',exact:true}).click();
  await page.locator('.ai-exchange').last().locator('.ai-business-card').first().waitFor();
  assert.equal(queries.at(-1).body.query,'帮我找午餐\n补充：运河商圈，人均100元以内');
  checks.push('clarification submits a complete bounded question instead of assuming server conversation memory');
  const latest=page.locator('.ai-exchange').last();
  await latest.getByRole('button',{name:'对比前3家店铺',exact:true}).click();
  await latest.locator('.ai-comparison-shop').first().waitFor();
  assert.equal(await latest.locator('.ai-comparison-shop').count(),3);
  assert.ok((await latest.locator('.ai-comparison-shop').first().textContent()).includes('本次查到 2 张券'));
  assert.ok((await latest.locator('.ai-comparison-shop').nth(1).textContent()).includes('未查询到券信息'));
  await page.screenshot({path:path.join(out,'comparison-desktop.png'),fullPage:true});
  await page.setViewportSize({width:320,height:900});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2));
  await latest.locator('.ai-comparison').screenshot({path:path.join(out,'comparison-320.png')});
  await page.setViewportSize({width:1280,height:900});
  await latest.getByRole('button',{name:'收起店铺对比',exact:true}).click();
  checks.push('candidate comparison joins only queried coupons and does not turn unknown into unavailable');
  assert.equal(await latest.locator('.ai-business-card').count(),5);
  await latest.getByRole('button',{name:/展开全部结果/}).click();
  await page.waitForFunction(()=>document.querySelectorAll('.ai-exchange:last-child .ai-business-card').length===7);
  assert.equal(await latest.locator('.ai-business-card').count(),7);
  assert.equal(await latest.getByText('券价 ¥0',{exact:true}).count(),0);
  checks.push('shop and coupon groups support expansion and unknown prices are not free');
  await latest.getByRole('button',{name:/优惠券使用说明/}).click();
  await latest.locator('.ai-original').waitFor();assert.equal(await page.evaluate(()=>window.__sourceScript),undefined);
  checks.push('source evidence stays available and untrusted markup renders as text');
  await latest.getByRole('button',{name:'查这家店的券',exact:true}).first().click();
  await page.waitForFunction(()=>document.querySelectorAll('.ai-exchange').length===3&&!document.querySelector('.ai-exchange:last-child .ai-pending'));
  assert.equal(queries.at(-1).body.shopId,'1');assert.equal(queries.at(-1).path,'/api/agent/query');
  checks.push('shop recommendation continues into a scoped coupon query without placing an order');
  await page.locator('.ai-exchange').last().getByRole('button',{name:'查看并确认领取',exact:true}).first().click();
  await page.getByRole('button',{name:'确认创建待付款订单',exact:true}).waitFor();
  assert.equal(writes.length,0);await page.locator('.ai-claim-confirm').getByRole('button',{name:'取消',exact:true}).click();
  checks.push('paid coupon preview requires explicit order confirmation and cancellation performs no write');
  failNext=true;await input.fill('失败后重试');await page.getByRole('button',{name:'发送问题',exact:true}).click();
  await page.locator('.ai-exchange').last().getByRole('button',{name:'重新提问',exact:true}).waitFor();
  const failed=queries.at(-1);await page.locator('.ai-exchange').last().getByRole('button',{name:'重新提问',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('.ai-exchange:last-child .ai-pending'));
  assert.deepEqual(queries.at(-1),failed);checks.push('retry repeats the exact original read-only question');
  await input.fill('慢查询测试');await page.getByRole('button',{name:'发送问题',exact:true}).click();
  await page.getByText('还在等待查询回复，你不需要重复发送。',{exact:true}).waitFor({timeout:12000});
  assert.equal(await page.getByRole('button',{name:'正在回答…',exact:true}).isDisabled(),true);
  await releaseSlow();await page.waitForFunction(()=>!document.querySelector('.ai-exchange:last-child .ai-pending'));
  checks.push('slow requests show honest waiting and keep duplicate submission disabled');
  for(const width of [1440,1024,390,320]){
   await page.setViewportSize({width,height:900});
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2),'overflow '+width);
   await page.screenshot({path:path.join(out,'assistant-'+width+'.png'),fullPage:true});
  }
  checks.push('populated AI conversation and actions fit desktop and narrow mobile layouts');
  await page.getByRole('button',{name:'清空问答',exact:true}).click();await page.getByRole('heading',{name:'想找哪家店，或查什么优惠？',exact:true}).waitFor();assert.equal(await page.locator('.ai-exchange').count(),0);
  await page.evaluate(()=>window.scrollTo({top:0,behavior:'instant'}));
  const sendBox=await page.getByRole('button',{name:'发送问题',exact:true}).boundingBox();
  const navBox=await page.locator('.mobile-bottom-nav').boundingBox();
  await page.screenshot({path:path.join(out,'assistant-welcome-320.png'),fullPage:true});
  assert.ok(sendBox.y+sendBox.height<navBox.y,'mobile send button must be reachable above navigation on initial 320x900 view: '+JSON.stringify({sendBox,navBox}));
  checks.push('clearing the question history restores an uncluttered starting view');
  await input.fill('问'.repeat(195));await page.getByRole('button',{name:'发送问题',exact:true}).click();
  await page.locator('.ai-exchange').last().getByRole('button',{name:'补充筛选条件',exact:true}).waitFor();
  await page.locator('.ai-exchange').last().getByRole('button',{name:'补充筛选条件',exact:true}).click();
  await page.getByText('编辑这次需求',{exact:true}).waitFor();assert.equal(await input.getAttribute('maxlength'),'200');
  await input.fill('修改后的完整问题');await page.getByRole('button',{name:'重新查询',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('.ai-exchange:last-child .ai-pending'));
  assert.equal(queries.at(-1).body.query,'修改后的完整问题');
  checks.push('long questions switch to explicit editing rather than silent truncation');
  assert.deepEqual(errors,[]);assert.deepEqual(writes,[]);
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({checkedAt:new Date().toISOString(),checks,pageErrors:errors,readOnlyModelRequests:queries.length,blockedBusinessWrites:writes,realModelCalls:0,realBusinessWrites:0,apiFixtures:true,failures:0},null,2));
  console.log(JSON.stringify({checks:checks.length,failures:0,realModelCalls:0,realBusinessWrites:0}));
 }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exit(1);});
