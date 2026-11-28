"""Committed cancellation interrupted before Redis release; isolated real JAR only."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import time

spec=importlib.util.spec_from_file_location('ack_support',Path(__file__).with_name('verify-commit-ack-loss.py'))
ack=importlib.util.module_from_spec(spec);spec.loader.exec_module(ack)
PROJECT='citymate-expiry-p6'
APP=PROJECT+'-app'
SKU=981001
ack.PROJECT=PROJECT;ack.APP=APP;ack.BROKER=PROJECT+'-rocketmq-1';ack.MQ_NETWORK=PROJECT+'_mq'
r=ack.recovery;r.PROJECT=PROJECT;r.APP=APP


def state(h,oid):
    assert str(oid).isdigit()
    value=json.loads(h.sql(f"""SELECT JSON_OBJECT(
      'orderStatus',(SELECT status FROM tb_voucher_order WHERE id={oid}),
      'deadline',(SELECT CAST(expire_at AS CHAR) FROM tb_voucher_order WHERE id={oid}),
      'deadlineSeconds',(SELECT TIMESTAMPDIFF(SECOND,create_time,expire_at) FROM tb_voucher_order WHERE id={oid}),
      'releaseEvents',(SELECT COUNT(*) FROM tb_business_outbox WHERE aggregate_id={oid} AND kind='SECKILL_RELEASE'),
      'releaseDone',(SELECT COUNT(*) FROM tb_business_outbox WHERE aggregate_id={oid} AND kind='SECKILL_RELEASE' AND state='DONE'),
      'releaseInbox',(SELECT COUNT(*) FROM tb_message_inbox i JOIN tb_business_outbox b ON b.id=i.event_id WHERE b.aggregate_id={oid} AND b.kind='SECKILL_RELEASE' AND i.consumer_group='activities'),
      'releaseEventId',(SELECT id FROM tb_business_outbox WHERE aggregate_id={oid} AND kind='SECKILL_RELEASE'),
      'closedEvents',(SELECT COUNT(*) FROM tb_business_outbox WHERE aggregate_id={oid} AND kind='ORDER_CLOSED'))"""))
    value.update(h.state(SKU))
    value['releaseMarker']=h.redis('GET','seckill:released:'+oid)
    value['releaseMarkerTtl']=h.redis('TTL','seckill:released:'+oid)
    value['qualificationOrderId']=h.redis('HGET','seckill:requests:'+str(SKU),str(SKU*10000))
    return value


def validate_pending(v):
    assert v['orderStatus']==4 and v['orders']==1,v
    assert v['databaseRemaining']==2 and int(v['redisRemaining'])==1,v
    assert v['releaseEvents']==v['closedEvents']==1,v
    assert v['releaseDone']==v['releaseInbox']==0 and v['releaseMarker'] is None,v


def validate_final(v,oid,event):
    assert v['orderStatus']==4 and v['orders']==v['successfulReservations']==v['fulfillmentInbox']==1,v
    assert v['releaseEvents']==v['closedEvents']==v['releaseDone']==v['releaseInbox']==1,v
    assert v['releaseEventId']==event and v['releaseMarker']==v['qualificationOrderId']==oid,v
    assert v['releaseMarkerTtl']==-1,v
    assert v['databaseRemaining']==int(v['redisRemaining'])==2,v
    assert v['duplicateOwners']==v['unfinishedFulfillmentEvents']==v['streamPendingGlobal']==0,v


def run(h,folder,port):
    h.sql(f"""INSERT INTO tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,status)
      VALUES({SKU},1,'phase6 expiry crash','isolated paid fixture',100,100,1,1);
      INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time)
      VALUES({SKU},2,TIMESTAMPADD(HOUR,-1,NOW()),TIMESTAMPADD(DAY,1,NOW()));
      INSERT INTO tb_seckill_activity(voucher_id,total_stock) VALUES({SKU},2)""")
    h.login_tokens(SKU,1);h.publish(SKU,port)
    token='p6-'+str(SKU)+'-0'
    c=dict(voucherId=SKU,initialStock=2,correct=False,startedAt=r.now())
    path=folder/(str(SKU)+'-evidence.json')
    def save():r.write(path,c)
    c['applicationBefore']=r.inspect_app(h)
    oid=str(r.request(port,'POST','/voucher-order/seckill/'+str(SKU),token)['data'])
    assert oid.isdigit();c['acceptedOrderId']=oid;save()
    until=time.monotonic()+40
    while time.monotonic()<until:
        initial=state(h,oid)
        if initial['fulfillmentInbox']==1:break
        time.sleep(.5)
    else:raise AssertionError(('Paid order did not fulfill',initial))
    assert initial['orderStatus']==1 and initial['databaseRemaining']==int(initial['redisRemaining'])==1,initial
    assert 58<=initial['deadlineSeconds']<=61,initial
    c['fulfilledUnpaid']=initial
    c['brokerBefore']=ack.network_state(h,ack.BROKER)
    assert set(c['brokerBefore']['networks'])=={ack.MQ_NETWORK}
    ip=c['brokerBefore']['networks'][ack.MQ_NETWORK]
    assert ack.tcp_probe(h,ip)['returncode']==0
    cut=False
    try:
        h.command(['docker','network','disconnect',ack.MQ_NETWORK,ack.BROKER],text=True);cut=True
        c['brokerDisconnected']=ack.network_state(h,ack.BROKER)
        assert c['brokerDisconnected']['running'] and not c['brokerDisconnected']['networks']
        c['tcpDuringCut']=ack.tcp_probe(h,ip);assert c['tcpDuringCut']['returncode'] in (1,124)
        save();print('Broker partitioned; waiting for natural expiry and scheduled database cancellation.',flush=True)
        until=time.monotonic()+155
        while time.monotonic()<until:
            pending=state(h,oid)
            if pending['orderStatus']==4:break
            time.sleep(1)
        else:raise AssertionError(('Scheduled expiry did not commit',pending))
        validate_pending(pending);c['committedCancellationBeforeKill']=pending;save()
        h.command(['docker','kill','--signal','KILL',APP],text=True)
        c['applicationKilled']=r.inspect_app(h)
        assert c['applicationKilled']['exitCode']==137 and not c['applicationKilled']['oomKilled']
        c['afterKill']=state(h,oid);validate_pending(c['afterKill']);save()
        ack.connect_broker(h,ip);cut=False
        c['brokerRestored']=ack.network_state(h,ack.BROKER)
        for key in ('containerId','pid','startedAt'):assert c['brokerRestored'][key]==c['brokerBefore'][key]
        assert r.inspect_app(h)['state']=='exited'
        began=time.monotonic();h.command(['docker','start',APP],text=True);r.wait_healthy(port)
        c['applicationRestarted']=r.inspect_app(h)
        for key in ('containerId','imageId'):assert c['applicationRestarted'][key]==c['applicationBefore'][key]
        until=time.monotonic()+220
        while time.monotonic()<until:
            final=state(h,oid)
            if final['releaseDone']==final['releaseInbox']==1:break
            time.sleep(1)
        else:raise AssertionError(('Release task did not recover',final))
        validate_final(final,oid,pending['releaseEventId'])
        c['recoveryObservationSeconds']=time.monotonic()-began;c['recovered']=final;save()
        retry=r.request(port,'POST','/voucher-order/seckill/'+str(SKU),token)
        assert str(retry['data'])==oid,retry
        time.sleep(12)
        c['final']=state(h,oid);validate_final(c['final'],oid,pending['releaseEventId'])
        c['httpRetryReturnedOriginalOrderId']=True;c['correct']=True;c['completedAt']=r.now();save()
        print(json.dumps(dict(correct=True,orderStatus=4,releaseEvents=1,releaseInbox=1,remainingStock=2)),flush=True)
        return c
    finally:
        if cut:ack.connect_broker(h,ip)


def main():
    p=argparse.ArgumentParser();p.add_argument('--work-dir',type=Path,required=True)
    p.add_argument('--run-id',required=True);p.add_argument('--port',type=int,default=18091);a=p.parse_args()
    assert re.fullmatch('[a-f0-9]{32}',a.run_id)
    h=r.load_helpers();assert h.sql('SELECT DATABASE()')=='hmdp_optimization_test'
    assert r.pairs(h.redis('CONFIG','GET','appendfsync'))=={'appendfsync':'always'}
    report=dict(runId=a.run_id,generatedAt=r.now(),executionMode='packaged-jar-expiry-crash',
      productionHooksAdded=False,manualBusinessRepairUsed=False,manualMessageReplayUsed=False,
      brokerProcessRestarted=False,databaseOrRedisCrashed=False,cases=[],passed=False)
    try:
        report['cases'].append(run(h,a.work_dir,a.port));report['passed']=True
    finally:r.write(a.work_dir/'application.json',report)

if __name__=='__main__':main()
