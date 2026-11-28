// Real browser + real model transport. Only a labelled free demo voucher may be claimed.
const {chromium}=require('../target/ui-audit-tools/node_modules/playwright-core');
const {execFileSync}=require('child_process');
const {pathToFileURL}=require('url');
const fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert/strict');
(async()=>{
 const {parseResponseText}=await import(pathToFileURL(path.resolve('frontend/scripts/api.js')));
 const base='http://127.0.0.1:8080',run=crypto.randomUUID().replaceAll('-','');
 const out=path.resolve('target/agent-live-journey',run);fs.mkdirSync(out,{recursive:true});
 const token=crypto.randomBytes(32).toString('hex'),key='login:token:'+token;
 const report={runId:run,checkedAt:new Date().toISOString(),syntheticUserId:'880001',shopId:'880001',voucherId:'880101',
  browserResponsesMocked:false,credentialRead:false,cases:[],checks:[],businessRequests:[],orderRetained:false,
  temporaryLoginRemoved:false,realPayment:false,semanticReview:'PENDING'};
 report.jarSha256=crypto.createHash('sha256').update(fs.readFileSync(path.resolve('target/citymate-0.0.1-SNAPSHOT.jar'))).digest('hex');
 let browser,created=false;
 function command(args,input){try{return execFileSync('docker',args,{input,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();}catch{throw Error('Local demo fixture operation failed');}}
 function sql(query){return command(['exec','-i','citymate-mysql-1','sh','-c',
  'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names hmdp'],query);}
 function redis(...args){return command(['exec','citymate-redis-1','redis-cli','--raw',...args]);}
 function save(){fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(report,null,2));}
 function check(name,passed,detail){report.checks.push({name,passed:!!passed,...(detail?{detail}: {})});save();}
 async function read(route,login=false){const response=await fetch(base+'/api'+route,{headers:login?{authorization:token}:{}});assert.equal(response.status,200);return parseResponseText(await response.text());}
 try{
  assert.equal((await read('/actuator/health')).status,'UP');
  assert.equal(sql("SELECT COUNT(*) FROM tb_user WHERE id=880001 AND phone LIKE 'demo%';"),'1');
  const shop=(await read('/shop/880001')).data;
  const voucher=(await read('/voucher/list/880001')).data.find(v=>String(v.id)==='880101');
  assert.ok(shop.name.includes('演示')&&voucher.title.includes('演示')&&Number(voucher.payValue)===0&&Number(voucher.type)===0);
  const before=Number(sql('SELECT COUNT(*) FROM tb_voucher_order WHERE user_id=880001 AND voucher_id=880101;'));
  report.existingOrdersBefore=before;
  if(before>0)report.existingOrderId=sql('SELECT id FROM tb_voucher_order WHERE user_id=880001 AND voucher_id=880101 ORDER BY create_time DESC,id DESC LIMIT 1;');
  created=redis('EVAL',"if redis.call('EXISTS',KEYS[1])==1 then return 0 end redis.call('HSET',KEYS[1],'id','880001','nickName','CityMate E2E demo') redis.call('EXPIRE',KEYS[1],600) return 1",'1',key)==='1';
  assert.ok(created);
  browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
  const context=await browser.newContext({viewport:{width:1440,height:1000}});
  await context.addInitScript(value=>sessionStorage.setItem('token',value),token);
  const page=await context.newPage(),errors=[];page.on('pageerror',error=>errors.push(error.message));
  page.on('request',req=>{const url=new URL(req.url());if(req.method()==='POST'&&url.pathname.startsWith('/api/voucher-order/')){report.businessRequests.push({method:'POST',path:url.pathname});save();}});
  await page.goto(base+'/assistant.html',{waitUntil:'networkidle'});
  const input=page.getByRole('textbox',{name:'向 CityMate AI 提问'});
  await page.waitForFunction(()=>{const el=document.querySelector('#ai-question');return el&&!el.disabled;});
  async function ask(name,query,mode='agent'){
   await input.fill(query);const started=Date.now();
   const responsePromise=page.waitForResponse(r=>new URL(r.url()).pathname===(mode==='rules'?'/api/knowledge/answer':'/api/agent/query')&&r.request().method()==='POST',{timeout:40000});
   await page.getByRole('button',{name:'发送问题',exact:true}).click();
   const response=await responsePromise,payload=parseResponseText(await response.text()),data=payload.data||{};
   await page.waitForFunction(()=>!document.querySelector('.ai-exchange:last-child .ai-pending'),{},{timeout:40000});
   const record={name,query,http:response.status(),elapsedMs:Date.now()-started,status:data.status,answer:data.answer,
    sources:data.sources||[],steps:data.steps||[],observations:data.observations||[]};
   report.cases.push(record);save();console.log(JSON.stringify({stage:name,http:record.http,status:data.status,elapsedMs:record.elapsedMs}));
   await page.screenshot({path:path.join(out,name+'.png'),fullPage:true});
   if(data.status==='MODEL_NOT_CONFIGURED')throw Error('MODEL_NOT_CONFIGURED');
   return data;
  }
  const search=await ask('find-shops','找杭州两家名称带“演示”的美食店，人均50元以内，列出商圈和人均供我比较。请实际查询，不要领取或付款。');
  const latest=page.locator('.ai-exchange').last();
  check('real_model_shop_search',search.status==='ANSWERED'&&(search.observations||[]).some(o=>o.tool==='searchShops'&&o.result?.status==='OK'&&o.result.data?.items?.length>=2));
  const compare=latest.getByRole('button',{name:/对比前[23]家店铺/});
  if(await compare.count()){
   await compare.click();await latest.locator('.ai-comparison-shop').first().waitFor();
   check('comparison_of_live_tool_results',await latest.locator('.ai-comparison-shop').count()>=2);
   await page.screenshot({path:path.join(out,'comparison.png'),fullPage:true});
  }else check('comparison_of_live_tool_results',false,'No comparable live candidates');
  await page.getByRole('button',{name:'使用规则',exact:true}).click();
  const rules=await ask('platform-rules','秒杀有订单号就算领取成功了吗？订单生成就代表付款了吗？请查询平台规则，用普通用户能懂的话解释。','rules');
  check('rag_answer_has_current_source',rules.status==='ANSWERED'&&rules.sources?.length>0);
  for(const source of (rules.sources||[]).slice(0,3)){
   if(!/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(source.sourceKey||'')||!Number.isInteger(source.version)){check('source_reference_valid',false);continue;}
   const sourceData=await read('/knowledge/sources/'+encodeURIComponent(source.sourceKey)+'?version='+source.version);
   check('source_version_resolves',sourceData.success===true);
  }
  await page.getByRole('button',{name:'找店查券',exact:true}).click();
  const scope=page.locator('.ai-context-controls');if(!await scope.evaluate(el=>el.open))await scope.locator('summary').click();
  await page.locator('#ai-shop-search').fill('桥边小火锅');await page.getByRole('button',{name:'搜索店铺',exact:true}).click();
  await page.locator('#ai-shop option[value="880001"]').waitFor({state:'attached'});
  await page.locator('#ai-shop').selectOption('880001');
  await page.locator('#ai-voucher option[value="880101"]').waitFor({state:'attached'});await page.locator('#ai-voucher').selectOption('880101');
  const coupons=await ask('coupon-rules','查询选定演示店铺的这张免费10元抵扣券，请说明券价、消费门槛和叠加限制。先不要领取或付款。');
  const matching=(coupons.observations||[]).flatMap(o=>o.tool==='listShopVouchers'&&o.result?.status==='OK'?o.result.data?.items||[]:[]).find(v=>String(v.voucherId)==='880101'&&String(v.shopId)==='880001');
  check('real_model_queries_selected_free_coupon',coupons.status==='ANSWERED'&&matching&&Number(matching.payCents)===0);
  if(coupons.status==='ANSWERED'&&matching){
   const card=page.locator('.ai-exchange').last().locator('.ai-business-card').filter({hasText:voucher.title});
   await card.getByRole('button',{name:'查看并确认领取',exact:true}).click();
   await page.getByRole('button',{name:'确认领取',exact:true}).waitFor();
   check('preview_does_not_create_an_order',Number(sql('SELECT COUNT(*) FROM tb_voucher_order WHERE user_id=880001 AND voucher_id=880101;'))===before&&report.businessRequests.length===0);
   await page.screenshot({path:path.join(out,'before-confirmation.png'),fullPage:true});
   const post=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/voucher-order/ordinary/880101'&&r.request().method()==='POST');
   await page.getByRole('button',{name:'确认领取',exact:true}).click();
   const result=parseResponseText(await (await post).text());assert.equal(result.success,true);
   const orderId=String(result.data);assert.ok(/^[1-9]\d{0,18}$/.test(orderId));report.orderId=orderId;report.orderRetained=true;save();
   await page.locator('.ai-claim-result').waitFor();
   const order=(await read('/voucher-order/'+orderId,true)).data;
   check('free_demo_order_held_in_database',String(order.id)===orderId&&Number(order.status)===2&&String(order.voucherId)==='880101');
   check('only_one_explicit_free_claim_post',report.businessRequests.length===1&&report.businessRequests[0].path==='/api/voucher-order/ordinary/880101');
   report.newOrdersCreated=Number(sql('SELECT COUNT(*) FROM tb_voucher_order WHERE user_id=880001 AND voucher_id=880101;'))-before;
   check('claim_creates_one_or_reuses_existing_order',before===0?report.newOrdersCreated===1:report.newOrdersCreated===0&&orderId===report.existingOrderId);
   await page.locator('#ai-shop').selectOption('');
   const status=await ask('order-result','请实际查询我的订单，核对订单号'+orderId+'是否已领取。这是刚才确认的演示免费券，请区分领取与真实付款。');
   const found=(status.observations||[]).some(o=>o.tool==='listMyOrders'&&o.result?.status==='OK'&&(o.result.data?.items||[]).some(i=>String(i.orderId)===orderId));
   check('real_model_order_result_matches_owned_order',status.status==='ANSWERED'&&found);
   const owned=(status.observations||[]).flatMap(o=>o.tool==='listMyOrders'&&o.result?.status==='OK'?o.result.data?.items||[]:[]).find(i=>String(i.orderId)===orderId);
   check('received_state_has_explicit_tool_meaning',Number(owned?.status)===2&&owned?.statusMeaning==='免费券已领取，不涉及真实收款');
   check('answer_confirms_received_without_unknown_state',/已领取|领取成功/.test(status.answer||'')&&!/(?:暂不能|尚不能|无法|不能)确认.{0,6}领取/.test(status.answer||''));
  }else check('claim_and_result_stages',false,'No successful real-model coupon result; no claim performed');
  await page.setViewportSize({width:390,height:900});await page.screenshot({path:path.join(out,'final-mobile.png'),fullPage:true});
  check('no_browser_runtime_errors',errors.length===0,errors.join('; '));
 }catch(error){report.error=error.message;console.log(JSON.stringify({stage:'stopped',error:error.message}));}
 finally{
  if(browser)await browser.close();
  if(created)report.temporaryLoginRemoved=redis('DEL',key)==='1';
  report.allAutomatedChecksPassed=!report.error&&report.checks.length>0&&report.checks.every(c=>c.passed)&&report.temporaryLoginRemoved;
  report.topLevelModelRequests=report.cases.length;save();console.log(JSON.stringify({runId:run,allAutomatedChecksPassed:report.allAutomatedChecksPassed,requests:report.cases.length,temporaryLoginRemoved:report.temporaryLoginRemoved}));
 }
 if(!report.allAutomatedChecksPassed)process.exitCode=1;
})().catch(()=>{console.error('Live journey setup failed; check local services and demo fixtures.');process.exitCode=1;});
