// Isolated static frontend and API fixtures; never connects to a real backend.
const {chromium}=require('../target/ui-audit-tools/node_modules/playwright-core');
const http=require('http'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const root=path.resolve('frontend'),out=path.resolve('target/voucher-state-checks');fs.mkdirSync(out,{recursive:true});
 const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml','.woff':'font/woff','.woff2':'font/woff2'};
 const server=http.createServer((req,res)=>{
  let file;
  try{const name=decodeURIComponent(new URL(req.url,'http://localhost').pathname);file=path.resolve(root,'.'+name);}
  catch{res.writeHead(400);return res.end();}
  if(!file.startsWith(root+path.sep)&&file!==root){res.writeHead(403);return res.end();}
  if(file===root||file.endsWith('.html'))file=path.join(root,'index.html');
  fs.readFile(file,(error,bytes)=>{if(error){res.writeHead(404);res.end();}else{res.writeHead(200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream'});res.end(bytes);}});
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 let browser;
 const checks=[],errors=[],writes=[],catalog=[];
 try{
  browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
  const context=await browser.newContext();
  let failShops=true,failCatalog=false;
  const rows=[{id:'101',shopId:'1',shopName:'运河火锅',typeId:'1',type:0,status:1,title:'火锅 & 代金券',payValue:4500,actualValue:5000,orderable:true},
    {id:'102',shopId:'2',shopName:'西湖KTV',typeId:'2',type:0,status:1,title:'欢唱优惠券',payValue:8000,actualValue:10000,orderable:true}];
  const reply=(r,data,total)=>r.fulfill({json:{success:true,data,total}});
  await context.route('**/api/**',async r=>{
   const request=r.request(),url=new URL(request.url()),p=url.pathname,q=url.searchParams;
   if(request.method()!=='GET'){writes.push(p);return r.abort();}
   if(p==='/api/shop-type/list')return reply(r,[{id:1,name:'美食'},{id:2,name:'KTV'}]);
   if(p==='/api/voucher/shops')return failShops?r.fulfill({status:503,json:{success:false}}):reply(r,{shops:rows.filter(s=>!q.get('typeId')||s.typeId===q.get('typeId')).map(s=>({id:s.shopId,name:s.shopName})),hasMore:false});
   if(p==='/api/voucher/catalog'){
    catalog.push(Object.fromEntries(q));
    if(failCatalog)return r.fulfill({status:503,json:{success:false}});
    const result=rows.filter(s=>(!q.get('keyword')||(s.title+s.shopName).includes(q.get('keyword')))&&(!q.get('typeId')||s.typeId===q.get('typeId'))&&(!q.get('shopId')||s.shopId===q.get('shopId')));
    return reply(r,result,result.length);
   }
   throw new Error('Unexpected fixture API '+p);
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  const base='http://127.0.0.1:'+server.address().port;
  await page.goto(base+'/vouchers.html?keyword='+encodeURIComponent('代金券')+'&type=1&shopId=1&ref=friend#offers',{waitUntil:'networkidle'});
  assert.equal(await page.getByLabel('搜索店铺或优惠券').inputValue(),'代金券');
  assert.equal(await page.getByRole('button',{name:'美食',exact:true}).getAttribute('aria-pressed'),'true');
  assert.equal(await page.getByLabel('具体店铺').inputValue(),'1');
  assert.equal(await page.locator('.voucher-card').count(),1);
  checks.push('shared URL restores keyword category and selected shop');
  await page.getByRole('button',{name:'重新加载店铺',exact:true}).waitFor();
  assert.equal(await page.getByLabel('具体店铺').locator('option:checked').innerText(),'当前筛选店铺');
  checks.push('failed shop options retain an explicit selection and voucher results');
  const reads=catalog.length;failShops=false;
  await page.getByRole('button',{name:'重新加载店铺',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#offer-shop option:checked')?.textContent==='运河火锅');
  assert.equal(catalog.length,reads);checks.push('retrying shop options does not query or clear vouchers');
  await page.getByLabel('搜索店铺或优惠券').fill('火锅 & 代金券');await page.getByRole('button',{name:'搜索优惠',exact:true}).click();
  await page.waitForFunction(()=>new URL(location.href).searchParams.get('keyword')==='火锅 & 代金券');
  const submitted=new URL(page.url());assert.equal(submitted.searchParams.get('ref'),'friend');assert.equal(submitted.hash,'#offers');
  checks.push('submitted Unicode filters update URL while keeping unrelated parameters');
  await page.reload({waitUntil:'networkidle'});
  assert.equal(await page.getByLabel('搜索店铺或优惠券').inputValue(),'火锅 & 代金券');
  assert.equal(await page.getByLabel('具体店铺').inputValue(),'1');assert.equal(await page.locator('.voucher-card').count(),1);
  checks.push('refresh restores the submitted combination');
  await page.getByRole('button',{name:'KTV',exact:true}).click();
  await page.waitForFunction(()=>new URL(location.href).searchParams.get('type')==='2');
  assert.equal(new URL(page.url()).searchParams.has('shopId'),false);
  checks.push('category switch clears the old shop from the URL');
  await page.getByRole('button',{name:'清空筛选',exact:true}).click();
  await page.waitForFunction(()=>document.querySelectorAll('.voucher-card').length===2);
  const reset=new URL(page.url());for(const k of ['keyword','type','shopId'])assert.equal(reset.searchParams.has(k),false);
  checks.push('clear filters resets the URL and results');
  failCatalog=true;await page.getByLabel('搜索店铺或优惠券').fill('代金券');await page.getByRole('button',{name:'搜索优惠',exact:true}).click();
  await page.getByRole('alert').waitFor();await page.getByLabel('搜索店铺或优惠券').fill('未提交的新搜索');
  failCatalog=false;await page.getByRole('alert').getByRole('button',{name:'重试',exact:true}).click();
  await page.waitForFunction(()=>document.querySelectorAll('.voucher-card').length===1);
  assert.equal(catalog.at(-1).keyword,'代金券');assert.equal(new URL(page.url()).searchParams.get('keyword'),'代金券');
  checks.push('first-page retry retains the failed submitted query rather than unsent edits');
  for(const width of [1440,390,320]){
   await page.setViewportSize({width,height:900});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2));
   await page.screenshot({path:path.join(out,'vouchers-'+width+'.png'),fullPage:true});
  }
  checks.push('filter and retry controls fit desktop and narrow mobile viewports');
  assert.deepEqual(errors,[]);assert.deepEqual(writes,[]);
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({checkedAt:new Date().toISOString(),checks,pageErrors:errors,writeAttempts:writes,apiFixtures:true,realBusinessWrites:0,failures:0},null,2));
  console.log(JSON.stringify({checks:checks.length,failures:0,realBusinessWrites:0}));
 }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exit(1);});
