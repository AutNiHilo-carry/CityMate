// Isolated browser fixtures exercise UI state without uploading or publishing to the real server.
const {chromium}=require('../target/ui-audit-tools/node_modules/playwright-core');
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const out=path.resolve('target/business-ui-checks');fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const steps=[],writes=[],errors=[];
 try{
  const context=await browser.newContext();
  await context.addInitScript(()=>sessionStorage.setItem('token','business-ui-fixture'));
  let identity='991',uploads=0,voucherReads=0;
  const shop={id:99,name:'业务界面测试店铺',area:'测试商圈',address:'测试地址',typeId:1,score:48,avgPrice:50,images:'',openHours:'10:00—22:00'};
  const coupon={id:11,shopId:99,title:'50元代金券',subTitle:'周末可用',rules:'仅限堂食\\n周末可用',payValue:4750,actualValue:5000,type:0,status:1,orderable:true,shopName:shop.name};
  const reply=(r,data,total)=>r.fulfill({json:{success:true,data,total}});
  await context.route('**/api/**',async route=>{
   const req=route.request(),u=new URL(req.url()),p=u.pathname;
   if(req.method()!=='GET'){
    writes.push({path:p,method:req.method(),stubbed:true});
    if(p==='/api/upload/blog')return reply(route,`/blogs/1/2/${++uploads===1?'aaaa':'bbbb'}.png`);
    if(p==='/api/blog')return route.fulfill({status:500,json:{success:false,errorMsg:'fixture timeout'}});
    throw new Error('Unexpected UI write: '+p);
   }
   if(p==='/api/user/me')return reply(route,{id:identity,nickName:'界面测试账号',icon:''});
   if(p==='/api/shop-type/list')return reply(route,[{id:1,name:'美食'}]);
   if(p==='/api/shop/catalog')return reply(route,[{...shop,id:1,name:'其他店铺'}],1);
   if(p==='/api/shop/99')return reply(route,shop);
   if(p==='/api/voucher/catalog')return reply(route,[coupon],1);
   if(p==='/api/voucher/shops')return reply(route,{shops:[{id:99,name:shop.name}],hasMore:false});
   if(p==='/api/voucher/list/99'){
    if(++voucherReads===1)return route.fulfill({status:503,json:{success:false}});
    return reply(route,[coupon]);
   }
   if(p==='/api/blog/of/shop'){
    const ids=u.searchParams.get('current')==='1'?[1,2,3,4,5]:[6];
    return reply(route,ids.map(id=>({id,shopId:99,userId:991,title:`测试探店笔记 ${id}`,name:'测试作者',liked:0,isLike:false,images:'/blogs/1/2/aaaa.png'})),6);
   }
   throw new Error('Unexpected UI API: '+p);
  });
  await context.route('**/imgs/blogs/**',r=>r.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="400" height="500"><rect width="400" height="500" fill="#eceff3"/><text x="100" y="250" fill="#999" font-size="24">TEST PHOTO</text></svg>'}));
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://127.0.0.1:8080/blog-edit.html?shop=99',{waitUntil:'networkidle'});
  await page.keyboard.press('Tab');
  assert.equal(await page.locator('.skip-link').evaluate(e=>e===document.activeElement&&getComputedStyle(e).clipPath==='none'),true);
  await page.keyboard.press('Tab');
  assert.equal(await page.locator('.skip-link').evaluate(e=>getComputedStyle(e).clipPath!=='none'),true);
  steps.push('skip link is visible for keyboard focus only');
  assert.equal(await page.locator('#note-shop').inputValue(),'99');steps.push('preselected shop outside first search page retained');
  await page.locator('#note-title').fill('晚饭吃了火锅');
  await page.locator('#note-content').fill('分享环境、菜品和服务的真实体验。');
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1cAAAAASUVORK5CYII=','base64');
  await page.locator('#note-images').setInputFiles([{name:'one.png',mimeType:'image/png',buffer:png},{name:'two.png',mimeType:'image/png',buffer:png}]);
  await page.locator('.cover-button').waitFor();
  await page.locator('.cover-button').click();
  await page.waitForFunction(()=>JSON.parse(localStorage.getItem('citymate:note-draft:v1:991'))?.draft.pictures[0].includes('bbbb'));
  assert.ok((await page.locator('.publish-preview-card > img').getAttribute('src')).includes('bbbb'));
  steps.push('upload fixtures, cover reorder and automatic save');
  for(const width of [1440,390,320]){
   await page.setViewportSize({width,height:900});
   await page.evaluate(()=>{document.documentElement.style.scrollBehavior='auto';window.scrollTo(0,0);});
   await page.waitForTimeout(100);
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2));
   await page.screenshot({path:path.join(out,`publish-${width}.png`),fullPage:true});
  }
  steps.push('publish preview fits desktop and narrow screens');
  await page.reload({waitUntil:'networkidle'});
  await page.getByRole('heading',{name:'有一篇还没写完的草稿'}).waitFor();
  await page.getByRole('button',{name:'继续编辑',exact:true}).click();
  assert.equal(await page.locator('#note-title').inputValue(),'晚饭吃了火锅');
  assert.equal(await page.locator('#note-shop').inputValue(),'99');steps.push('explicit draft restoration after reload');
  await page.getByRole('button',{name:'发布笔记',exact:true}).click();
  await page.getByText('发布结果还未确认',{exact:true}).waitFor();
  assert.equal(await page.getByRole('button',{name:'发布笔记',exact:true}).isDisabled(),true);
  steps.push('uncertain publish result blocks repeat submission');
  identity='992';await page.reload({waitUntil:'networkidle'});
  assert.equal(await page.locator('#note-title').inputValue(),'');steps.push('draft isolation between accounts');
  identity='991';await page.reload({waitUntil:'networkidle'});
  await page.getByRole('button',{name:'继续编辑',exact:true}).click();
  await page.getByText('发布结果还未确认',{exact:true}).waitFor();steps.push('uncertain state survives draft restore');
  await page.getByRole('button',{name:'清空草稿',exact:true}).click();
  await page.getByRole('button',{name:'确认清空',exact:true}).click();
  assert.equal(await page.locator('#note-title').inputValue(),'');
  await page.waitForFunction(()=>localStorage.getItem('citymate:note-draft:v1:991')===null);steps.push('explicit clear removes current browser draft');
  await page.goto('http://127.0.0.1:8080/vouchers.html',{waitUntil:'networkidle'});
  await page.locator('.voucher-rules summary').first().click();
  assert.equal(await page.locator('.voucher-rules p').first().innerText(),'仅限堂食\n周末可用');steps.push('coupon rules expand as readable plain text');
  await page.goto('http://127.0.0.1:8080/shop-detail.html?id=99',{waitUntil:'networkidle'});
  await page.getByRole('button',{name:'重试优惠查询',exact:true}).waitFor();
  assert.equal(await page.locator('.blog-card').count(),5);
  await page.getByRole('button',{name:'查看更多探店记录',exact:true}).click();
  await page.waitForFunction(()=>document.querySelectorAll('.blog-card').length===6);steps.push('shop note pagination appends another page');
  await page.getByRole('button',{name:'重试优惠查询',exact:true}).click();
  await page.locator('.voucher-card').waitFor();steps.push('shop voucher errors retry independently of notes');
  assert.equal(errors.length,0);
  assert.equal(writes.filter(w=>w.path==='/api/blog').length,1);
  const report={steps,writes,realBusinessWrites:0,apiFixtures:true,pageErrors:errors,failures:0};
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify({checks:steps.length,stubbedWrites:writes.length,realBusinessWrites:0,failures:0}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
