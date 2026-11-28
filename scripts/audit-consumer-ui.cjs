// Consumer journeys against isolated fixtures, including responsive screenshots.
const {chromium}=require('../target/ui-audit-tools/node_modules/playwright-core');
const http=require('http'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
(async()=>{
 const root=path.resolve('frontend'),picturesRoot=path.resolve('../nginx-1.18.0/html/hmdp/imgs');
 const out=path.resolve('target/consumer-ui-checks');fs.mkdirSync(out,{recursive:true});
 const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml','.woff':'font/woff','.woff2':'font/woff2','.jpg':'image/jpeg','.png':'image/png','.webp':'image/webp'};
 const server=http.createServer((req,res)=>{
  let file,allowed;
  try{const name=decodeURIComponent(new URL(req.url,'http://localhost').pathname);allowed=name.startsWith('/imgs/')?picturesRoot:root;file=path.resolve(allowed,'.'+(allowed===picturesRoot?name.slice(5):name));}
  catch{res.writeHead(400);return res.end();}
  if(!file.startsWith(allowed+path.sep)&&file!==allowed){res.writeHead(403);return res.end();}
  if(file===root||file.endsWith('.html'))file=path.join(root,'index.html');
  fs.readFile(file,(error,bytes)=>{if(error){res.writeHead(404);res.end();}else{res.writeHead(200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream'});res.end(bytes);}});
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const base='http://127.0.0.1:'+server.address().port;
 const photo='/imgs/blogs/0/0/feb2a83e-5646-46c0-a982-5e43fed31b22.jpg';
 const shops=[{id:1,typeId:1,name:'运河边的茶餐厅',images:photo,area:'运河',address:'运河路 18 号',openHours:'10:00–22:00',avgPrice:50,score:48,comments:128},
  {id:2,typeId:2,name:'周末欢唱 KTV',images:photo,area:'西湖',address:'西湖路 8 号',avgPrice:80,score:45,comments:62}];
 const blogs=[1,2,3].map((id)=>({id,userId:'991',shopId:1,name:'周末探店的人',title:['运河边的午餐，简单又舒服','和朋友约一顿周末下午茶','店里值得尝试的几道小菜'][id-1],images:photo,content:'这次和朋友来运河边吃午餐，店内安静，出发前记得查看优惠券使用规则。',liked:id*3,isLike:false}));
 const vouchers=[{id:'101',shopId:1,shopName:shops[0].name,typeId:1,type:0,status:1,title:'午市代金券',subTitle:'堂食使用 · 请提前查看适用规则',payValue:4500,actualValue:5000,orderable:true,rules:'工作日午市可用，不与其他优惠叠加。'},
  {id:'102',shopId:2,shopName:shops[1].name,typeId:2,type:1,status:1,title:'周末欢唱券',payValue:8000,actualValue:10000,orderable:false,stock:0}];
 const orders=[1,2,4].map((status,index)=>({order_id:String(9000+index),title:vouchers[0].title,pay_cents:4500,status,voucher_type:0,shop_id:1,created_at:'2026-11-16T12:00:00',rules:vouchers[0].rules}));
 let browser;let authenticated=true;const checks=[],pages=[],errors=[],writes=[],saved=new Map();let favoriteWrites=0;
 try{
  browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
  const context=await browser.newContext();await context.addInitScript(()=>sessionStorage.setItem('token','consumer-ui-fixture'));
  const reply=(r,data,total)=>r.fulfill({json:{success:true,data,total}});
  await context.route('**/api/**',async r=>{
   const request=r.request(),url=new URL(request.url()),p=url.pathname,q=url.searchParams;
   if(p.startsWith('/api/favorites')){
    if(!authenticated)return r.fulfill({status:401,json:{success:false}});
    const parts=p.split('/'),kind=parts[3],id=parts[4];
    if(request.method()==='PUT'){favoriteWrites++;saved.set(kind+':'+id,request.postDataJSON().saved);return reply(r,request.postDataJSON().saved);}
    if(kind)return reply(r,saved.get(kind+':'+id)===true);
    const rows=[...saved].filter(([key,on])=>on&&key.startsWith(q.get('kind')+':')).map(([key])=>{
     const [kind,id]=key.split(':');const item=kind==='shop'?shops.find(s=>String(s.id)===id):blogs.find(b=>String(b.id)===id);
     return {kind,id,available:!!item,title:item?.name||item?.title,images:item?.images,area:item?.area,avgPrice:item?.avgPrice};
    });return reply(r,rows,rows.length);
   }
   if(request.method()!=='GET'){writes.push(p);return r.abort();}
   if(p==='/api/user/me')return authenticated?reply(r,{id:'991',nickName:'周末探店的人'}):r.fulfill({status:401,json:{success:false}});
   if(p==='/api/user/sign/count')return reply(r,3);
   if(p==='/api/shop-type/list')return reply(r,[{id:1,name:'美食'},{id:2,name:'KTV'}]);
   if(p==='/api/shop/catalog'){
    const rows=shops.filter(s=>(!q.get('keyword')||s.name.includes(q.get('keyword')))&&(!q.get('typeId')||String(s.typeId)===q.get('typeId')));
    return reply(r,rows,rows.length);
   }
   if(p==='/api/shop/1')return reply(r,shops[0]);
   if(p==='/api/blog/1')return reply(r,blogs[0]);
   if(['/api/blog/hot','/api/blog/of/me'].includes(p))return reply(r,blogs);
   if(p==='/api/blog/of/shop')return reply(r,blogs,blogs.length);
   if(p.startsWith('/api/voucher/list/'))return reply(r,vouchers.filter(v=>String(v.shopId)===p.split('/').at(-1)));
   if(p==='/api/voucher/shops')return reply(r,{shops:shops.filter(s=>!q.get('typeId')||String(s.typeId)===q.get('typeId')),hasMore:false});
   if(p==='/api/voucher/catalog'){
    const rows=vouchers.filter(v=>(!q.get('keyword')||(v.title+v.shopName).includes(q.get('keyword')))&&(!q.get('typeId')||String(v.typeId)===q.get('typeId'))&&(!q.get('shopId')||String(v.shopId)===q.get('shopId')));
    return reply(r,rows,rows.length);
   }
   if(p==='/api/voucher/preview')return reply(r,{voucherId:'101',shopId:'1',type:0,title:vouchers[0].title,rules:vouchers[0].rules,payCents:4500,valueCents:5000,availableAtQuery:true});
   if(p==='/api/voucher-order/mine')return reply(r,orders,orders.length);
   if(p==='/api/voucher-order/mine/vouchers')return reply(r,[orders[1]],1);
   throw new Error('Unexpected API '+p);
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  const routes=[['home','/'],['shops','/shop-list.html'],['shop','/shop-detail.html?id=1'],['note','/blog-detail.html?id=1'],['vouchers','/vouchers.html'],['orders','/orders.html'],['profile','/info.html'],['assistant','/assistant.html'],['login','/login.html'],['publish','/blog-edit.html']];
  for(const width of [1440,1024,800,390,320])for(const [name,url]of routes){
   await page.setViewportSize({width,height:900});await page.goto(base+url,{waitUntil:'networkidle'});
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2),name+' overflows at '+width);
   const nav=page.getByRole('navigation',{name:'手机主导航'});
   assert.equal(await nav.isVisible(),width<=760&&!['login','publish'].includes(name),name+' nav visibility');
   if(width<=760&&!['login','publish'].includes(name)){
    const box=await nav.boundingBox();assert.ok(box.height>=50&&box.y+box.height<=901);
   }
   await page.screenshot({path:path.join(out,name+'-'+width+'.png'),fullPage:true});pages.push({name,width});
   if(width===390&&['home','shop','vouchers','orders'].includes(name))await page.screenshot({path:path.join(out,name+'-viewport-390.png')});
  }
  checks.push('responsive navigation fits all ten consumer pages and stays hidden during login or editing');
  await page.setViewportSize({width:390,height:900});await page.goto(base+'/vouchers.html',{waitUntil:'networkidle'});
  await page.getByRole('button',{name:'美食',exact:true}).click();
  await page.getByLabel('具体店铺').selectOption('1');
  await page.getByRole('textbox',{name:'搜索优惠券或店铺',exact:true}).fill('午市');
  await page.getByRole('textbox',{name:'搜索优惠券或店铺',exact:true}).press('Enter');
  await page.waitForFunction(()=>new URL(location.href).searchParams.get('keyword')==='午市');
  assert.equal(new URL(page.url()).pathname,'/vouchers.html');assert.equal(new URL(page.url()).searchParams.get('shopId'),'1');assert.equal(new URL(page.url()).searchParams.get('type'),'1');
  assert.equal(await page.locator('.voucher-card').count(),1);checks.push('header voucher search keeps category and shop filters on the voucher page');
  await page.getByText('比券面少付 ¥5',{exact:true}).waitFor();checks.push('voucher shows face value and a correctly calculated saving');
  await page.getByRole('navigation',{name:'手机主导航'}).getByRole('link',{name:'订单',exact:true}).click();
  await page.waitForURL('**/orders.html');assert.equal(await page.getByRole('navigation',{name:'手机主导航'}).getByRole('link',{name:'订单',exact:true}).getAttribute('aria-current'),'page');
  checks.push('mobile order entry opens orders directly with correct active state');
  await page.goto(base+'/shop-detail.html?id=1',{waitUntil:'networkidle'});
  await page.getByRole('navigation',{name:'店铺页面内容'}).getByRole('link',{name:/优惠券/}).click();
  await page.waitForURL(url=>url.hash==='#shop-vouchers');
  await page.waitForFunction(()=>{const y=document.querySelector('#shop-vouchers').getBoundingClientRect().y;return y>=0&&y<100;});
  const section=await page.locator('#shop-vouchers').boundingBox();assert.ok(section.y>=0&&section.y<100);
  checks.push('shop shortcut scrolls to its real coupon section');
  await page.getByRole('button',{name:'购买优惠券',exact:true}).click();await page.locator('.dialog').waitFor();
  assert.equal(await page.locator('.mobile-bottom-nav').evaluate(e=>e.inert),true);
  await page.locator('.dialog').getByRole('button',{name:'再想想',exact:true}).click();
  checks.push('confirmation covers and disables mobile navigation without submitting a claim');
  await page.getByRole('button',{name:'放大店铺照片，第 1 张',exact:true}).click();
  await page.locator('.photo-viewer').waitFor();
  const top=await page.evaluate(()=>document.elementFromPoint(innerWidth/2,innerHeight-20).closest('.photo-viewer')!==null);assert.equal(top,true);
  await page.keyboard.press('Escape');await page.locator('.photo-viewer').waitFor({state:'hidden'});checks.push('photo viewer stays above the bottom navigation');
  await page.getByRole('navigation',{name:'手机主导航'}).getByRole('link',{name:'发现',exact:true}).click();
  await page.getByRole('textbox',{name:'搜索商家',exact:true}).fill('运河');await page.getByRole('textbox',{name:'搜索商家',exact:true}).press('Enter');
  await page.waitForURL('**/shop-list.html?keyword=*');assert.equal(new URL(page.url()).searchParams.get('keyword'),'运河');
  checks.push('discovery search opens the shop catalog with its keyword');
  const search='/shop-list.html?keyword='+encodeURIComponent('运河')+'&type=1&maxPrice=80';
  await page.goto(base+search,{waitUntil:'networkidle'});
  await page.locator('.shop-card').first().click();await page.waitForURL(u=>u.pathname==='/shop-detail.html');
  assert.equal(new URL(page.url()).searchParams.get('from'),search);
  await page.getByRole('link',{name:'返回搜索结果',exact:true}).click();await page.waitForURL(base+search);
  assert.equal(await page.getByLabel('人均预算').inputValue(),'80');
  checks.push('shop detail returns to the exact catalog filters without relying on browser history');
  const couponSearch='/vouchers.html?type=1&shopId=1&keyword='+encodeURIComponent('午市');
  await page.goto(base+couponSearch,{waitUntil:'networkidle'});await page.locator('.voucher-main > a').first().click();
  await page.getByRole('link',{name:'返回优惠券列表',exact:true}).click();await page.waitForURL(base+couponSearch);
  checks.push('coupon store links preserve category shop and keyword on return');
  await page.goto(base+'/',{waitUntil:'networkidle'});await page.locator('.blog-cover').first().click();
  await page.getByRole('link',{name:'返回发现页',exact:true}).waitFor();
  await page.locator('.note-shop').click();await page.getByRole('link',{name:'返回探店笔记',exact:true}).click();
  await page.waitForURL(u=>u.pathname==='/blog-detail.html');
  checks.push('note to store navigation returns to the originating note');
  await page.goto(base+'/shop-detail.html?id=1',{waitUntil:'networkidle'});
  await page.getByRole('button',{name:'收藏店铺',exact:true}).click();await page.getByRole('button',{name:'已收藏店铺',exact:true}).waitFor();
  await page.goto(base+'/info.html#my-favorites',{waitUntil:'networkidle'});assert.equal(await page.locator('.favorite-item').count(),1);
  await page.locator('#my-favorites').scrollIntoViewIfNeeded();
  await page.screenshot({path:path.join(out,'favorites-390.png'),fullPage:true});
  await page.setViewportSize({width:1440,height:900});await page.screenshot({path:path.join(out,'favorites-1440.png'),fullPage:true});
  await page.setViewportSize({width:390,height:900});
  await page.locator('.favorite-item').getByRole('button',{name:/取消收藏/}).click();await page.getByRole('heading',{name:'还没有想去的店',exact:true}).waitFor();
  checks.push('shop collection persists across page loads and confirmed removal refreshes the personal list');
  await page.goto(base+'/blog-detail.html?id=1',{waitUntil:'networkidle'});await page.getByRole('button',{name:'收藏笔记',exact:true}).click();await page.getByRole('button',{name:'已收藏笔记',exact:true}).waitFor();
  await page.goto(base+'/info.html#my-favorites',{waitUntil:'networkidle'});
  await page.getByRole('button',{name:'收藏的笔记',exact:true}).click();
  await page.waitForFunction(()=>new URL(location.href).searchParams.get('collection')==='note');
  await page.reload({waitUntil:'networkidle'});await page.locator('.favorite-item a').first().waitFor();
  assert.equal(await page.getByRole('button',{name:'收藏的笔记',exact:true}).getAttribute('aria-pressed'),'true');
  await page.locator('.favorite-item a').first().click();await page.getByRole('link',{name:'返回上一页',exact:true}).click();
  await page.waitForURL(u=>u.pathname==='/info.html'&&u.searchParams.get('collection')==='note');
  assert.equal(favoriteWrites,3);checks.push('note collection is separate and detail returns to the same collection tab');
  authenticated=false;
  await page.goto(base+'/shop-detail.html?id=1#shop-vouchers',{waitUntil:'networkidle'});
  await page.getByRole('link',{name:'登录 / 注册',exact:true}).click();await page.waitForURL(u=>u.pathname==='/login.html');
  assert.equal(new URL(page.url()).searchParams.get('return'),'/shop-detail.html?id=1#shop-vouchers');
  checks.push('header login preserves the current detail and coupon anchor');
  assert.deepEqual(errors,[]);assert.deepEqual(writes,[]);
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({checkedAt:new Date().toISOString(),pages,checks,pageErrors:errors,writeAttempts:writes,apiFixtures:true,realBusinessWrites:0,failures:0},null,2));
  console.log(JSON.stringify({pageViewportChecks:pages.length,interactionChecks:checks.length,failures:0,realBusinessWrites:0}));
 }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exit(1);});
