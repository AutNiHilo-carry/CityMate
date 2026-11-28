"""Synthetic fixtures and read-only reconciliation for an actual packaged application."""
import argparse
import collections
import hashlib
import http.client
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import time

from importlib.util import spec_from_file_location, module_from_spec

PROJECT = 'citymate-jar-p6'
APP = PROJECT + '-app'


def command(args, **kwargs):
    return subprocess.run(args, capture_output=True, check=True, timeout=30, **kwargs)


def sql(statement):
    result = command(['docker', 'exec', PROJECT+'-mysql-1', 'mysql', '-uroot',
        '-pisolation_test_only', '--batch', '--raw', '--skip-column-names',
        'hmdp_optimization_test', '-e', statement], text=True)
    return result.stdout.strip()


def redis(*args):
    result = command(['docker', 'exec', PROJECT+'-redis-1', 'redis-cli', '--json', *map(str,args)], text=True)
    if str(args[0]).upper()=='INFO':
        return result.stdout
    return json.loads(result.stdout)


def login_tokens(sku, count):
    commands = [['HSET','login:token:p6-jar-admin','id','1010'],
                ['EXPIRE','login:token:p6-jar-admin','3600']]
    for i in range(count):
        key = 'login:token:p6-'+str(sku)+'-'+str(i)
        commands += [['HSET',key,'id',str(sku*10000+i)], ['EXPIRE',key,'600']]
    wire = bytearray()
    for args in commands:
        wire += ('*'+str(len(args))+'\r\n').encode()
        for arg in args:
            value = arg.encode('utf-8')
            wire += ('$'+str(len(value))+'\r\n').encode()+value+b'\r\n'
    result = command(['docker','exec','-i',PROJECT+'-redis-1','redis-cli','--pipe'],input=bytes(wire))
    assert b'errors: 0' in result.stdout, result.stdout.decode('utf-8',errors='replace')


def publish(sku, port):
    connection = http.client.HTTPConnection('127.0.0.1',port,timeout=15)
    try:
        connection.request('POST','/ops/seckill/'+str(sku)+'/publish',
                           headers={'authorization':'p6-jar-admin'})
        response = connection.getresponse()
        value = json.loads(response.read())
        assert response.status==200 and value.get('success'), value
    finally:
        connection.close()


def state(sku):
    # Every interpolated identifier is a local integer fixture ID, never user input.
    assert isinstance(sku,int)
    query = f"""SELECT JSON_OBJECT(
      'orders',(SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id={sku}),
      'databaseRemaining',(SELECT stock FROM tb_seckill_voucher WHERE voucher_id={sku}),
      'successfulReservations',(SELECT COUNT(*) FROM tb_seckill_reservation WHERE voucher_id={sku} AND state='SUCCESS'),
      'unfinishedFulfillmentEvents',(SELECT COUNT(*) FROM tb_business_outbox b JOIN tb_seckill_reservation r ON r.order_id=b.aggregate_id WHERE b.kind='SECKILL_FULFILL' AND r.voucher_id={sku} AND b.state<>'DONE'),
      'fulfillmentInbox',(SELECT COUNT(*) FROM tb_message_inbox i JOIN tb_business_outbox b ON b.id=i.event_id JOIN tb_seckill_reservation r ON r.order_id=b.aggregate_id WHERE b.kind='SECKILL_FULFILL' AND i.consumer_group='fulfillment' AND r.voucher_id={sku}),
      'duplicateOwners',(SELECT COUNT(*)-COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id={sku}),
      'persistedIds',(SELECT JSON_ARRAYAGG(CAST(id AS CHAR)) FROM tb_voucher_order WHERE voucher_id={sku}))"""
    data = json.loads(sql(query))
    data['redisRemaining'] = redis('GET','seckill:stock:'+str(sku))
    data['streamPendingGlobal'] = redis('XPENDING','stream.orders','g1')[0]
    return data


def round_case(folder,run_id,sku,concurrency,requests,stock,port,driver):
    assert sql('SELECT DATABASE()')=='hmdp_optimization_test'
    assert sql(f'SELECT COUNT(*) FROM tb_voucher WHERE id={sku}')=='0'
    sql(f"""INSERT INTO tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,status)
      VALUES({sku},1,'phase6 packaged jar','isolated fixture',0,100,1,1);
      INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time)
      VALUES({sku},{stock},TIMESTAMPADD(HOUR,-1,NOW()),TIMESTAMPADD(DAY,1,NOW()));
      INSERT INTO tb_seckill_activity(voucher_id,total_stock) VALUES({sku},{stock});""")
    login_tokens(sku,requests)
    publish(sku,port)
    before = {'redisCommandStats':redis('INFO','commandstats')}
    began = time.perf_counter()
    driver.atomic(folder/(str(sku)+'-ready.json'),dict(runId=run_id,voucherId=sku,
        concurrency=concurrency,requests=requests,initialStock=stock,tokenPrefix='p6-'+str(sku)+'-'))
    payload = driver.wait_json(folder/(str(sku)+'-http.json'),180,run_id,APP)
    samples = payload['samples']
    assert len(samples)==requests
    counts = collections.Counter(row['kind'] for row in samples)
    accepted_ids = {row['orderId'] for row in samples if row['kind']=='accepted'}
    assert counts['accepted']==stock and len(accepted_ids)==stock, counts
    assert counts['sold_out']==requests-stock and counts['rate_limited']==0, counts
    assert sum(counts.values())==counts['accepted']+counts['sold_out'],counts
    backlog=[]
    deadline = time.perf_counter()+120
    while time.perf_counter()<deadline:
        data=state(sku)
        backlog.append(dict(secondsSinceStart=time.perf_counter()-began,
            orders=data['orders'],unfinishedFulfillmentEvents=data['unfinishedFulfillmentEvents'],
            streamPendingGlobal=data['streamPendingGlobal']))
        if data['orders']==stock and data['unfinishedFulfillmentEvents']==0 and data['streamPendingGlobal']==0:
            break
        time.sleep(.25)
    assert data['orders']==data['successfulReservations']==data['fulfillmentInbox']==stock,data
    assert data['databaseRemaining']==stock-len(accepted_ids)==int(data['redisRemaining']),data
    assert data['duplicateOwners']==data['unfinishedFulfillmentEvents']==data['streamPendingGlobal']==0,data
    assert set(data.pop('persistedIds') or [])==accepted_ids,data
    elapsed=time.perf_counter()-began
    latency=sorted(row['latencyMs'] for row in samples)
    result=dict(data,voucherId=sku,concurrency=concurrency,requests=requests,initialStock=stock,
        acceptedResponses=counts['accepted'],uniqueAcceptedRequests=len(accepted_ids),
        soldOutResponses=counts['sold_out'],rateLimitedResponses=0,systemErrors=0,
        admissionBatchSeconds=payload['admissionSeconds'],
        httpResponsesPerSecond=requests/payload['admissionSeconds'],
        acceptedRequestsPerAdmissionSecond=stock/payload['admissionSeconds'],
        allResponseP50Ms=latency[math.ceil(requests*.5)-1],
        allResponseP95Ms=latency[math.ceil(requests*.95)-1],
        allResponseP99Ms=latency[math.ceil(requests*.99)-1],
        completionObservationSeconds=elapsed,observedEndToEndOrdersPerSecond=stock/elapsed,
        completionPollSleepMs=250,acceptedIdsEqualPersistedIds=True,correct=True,
        samples=samples,backlogObservations=backlog,observationsBefore=before)
    driver.atomic(folder/(str(sku)+'-done.json'),result)
    return result


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--work-dir',type=Path,required=True)
    parser.add_argument('--run-id',required=True)
    parser.add_argument('--port',type=int,default=18087)
    args=parser.parse_args()
    assert re.fullmatch(r'[a-f0-9]{32}',args.run_id)
    assert sql('SELECT DATABASE()')=='hmdp_optimization_test'
    mode=redis('CONFIG','GET','appendfsync')
    aof=redis('CONFIG','GET','appendonly')
    if isinstance(mode,list): mode=dict(zip(mode[::2],mode[1::2]))
    if isinstance(aof,list): aof=dict(zip(aof[::2],aof[1::2]))
    assert mode=={'appendfsync':'always'} and aof=={'appendonly':'yes'},(mode,aof)
    path=Path(__file__).with_name('profile-transaction-load.py')
    spec=spec_from_file_location('load_driver',path)
    driver=module_from_spec(spec);spec.loader.exec_module(driver)
    folder=args.work_dir
    report=dict(runId=args.run_id,generatedAt=driver.now(),executionMode='packaged-jar',
        testOnlyRequestProbe=False,generatorSharesApplicationJvm=False,fixtureUsesSpring=False,
        database=sql('SELECT DATABASE()'),databaseVersion=sql('SELECT VERSION()'),
        redisFsync='always',redisAppendOnly='yes',brokerEnabled=True,automaticScheduling=True,
        mode='Finite closed-loop batches; one sample per concurrency; not maximum capacity',
        completionRateNote='Includes file coordination, docker-exec queries and polling; not steady-state TPS',
        fixtureNote='Synthetic logins and test vouchers; activity published through authenticated ops HTTP',
        rounds=[],passed=False)
    process=subprocess.Popen([sys.executable,str(path),'--work-dir',str(folder),'--run-id',args.run_id,
        '--port',str(args.port),'--app-container',APP,'--compose-project',PROJECT,'--packaged-jar'])
    try:
        report['warmup']=round_case(folder,args.run_id,970600,4,20,20,args.port,driver)
        for i,c in enumerate([8,16,32]):
            report['rounds'].append(round_case(folder,args.run_id,970601+i,c,200,100,args.port,driver))
        driver.atomic(folder/'finished.json',dict(runId=args.run_id,allRoundsReconciled=True))
        assert process.wait(timeout=30)==0
        report['passed']=True
    finally:
        driver.atomic(folder/'application.json',report)
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
    print('Packaged JAR reconciliation passed: '+args.run_id,flush=True)


if __name__=='__main__':
    main()
