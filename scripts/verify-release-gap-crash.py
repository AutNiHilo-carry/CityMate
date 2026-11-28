"""Interrupt after Redis release but before Inbox commit, then recover original task."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import time
import subprocess

spec=importlib.util.spec_from_file_location('ack_support',Path(__file__).with_name('verify-commit-ack-loss.py'))
ack=importlib.util.module_from_spec(spec);spec.loader.exec_module(ack)
PROJECT='citymate-release-gap-p6'
APP=PROJECT+'-app'
SKU=981101
ack.PROJECT=PROJECT;ack.APP=APP;ack.BROKER=PROJECT+'-rocketmq-1';ack.MQ_NETWORK=PROJECT+'_mq'
r=ack.recovery;r.PROJECT=PROJECT;r.APP=APP


def gate_statement(marker,event):
    assert re.fullmatch(r'[a-z0-9_]{1,64}',marker)
    assert isinstance(event,int) and event>0
    return ("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION; "
        f"SELECT event_id FROM tb_message_inbox WHERE consumer_group='activities' AND event_id={event} FOR UPDATE; "
        f"SELECT GET_LOCK('{marker}',0); DO SLEEP(180)")


class InboxInsertGate(r.DatabaseGate):
    def __init__(self,h,marker,event):
        assert h.sql('SELECT DATABASE()')=='hmdp_optimization_test'
        self.helper,self.marker,self.table=h,marker,'tb_message_inbox'
        self.connection_id=None
        self.process=subprocess.Popen(['docker','exec',PROJECT+'-mysql-1','mysql','-uroot',
            '-pisolation_test_only','--batch','--raw','--skip-column-names',
            'hmdp_optimization_test','-e',gate_statement(marker,event)],
            stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        until=time.monotonic()+15
        while time.monotonic()<until:
            owner=h.sql(f"SELECT IS_USED_LOCK('{marker}')")
            if owner.isdigit():self.connection_id=int(owner);return
            assert self.process.poll() is None,'Inbox gap holder exited before readiness'
            time.sleep(.2)
        self.release();raise AssertionError('Inbox gap lock not acquired')

    def blocked(self,event):
        text=self.helper.sql("""SELECT JSON_ARRAYAGG(JSON_OBJECT(
          'transactionId',CAST(trx_id AS CHAR),'connectionId',trx_mysql_thread_id,
          'state',trx_state,'sql',LEFT(trx_query,1600),'isolationLevel',trx_isolation_level))
          FROM information_schema.INNODB_TRX WHERE trx_state='LOCK WAIT'
          AND trx_query LIKE '%tb_message_inbox%'""")
        rows=[] if text=='NULL' else (json.loads(text) or [])
        return [v for v in rows if target_insert(v['sql'],event)]


def target_insert(sql,event):
    return bool(re.match(r'\s*INSERT\s+(?:IGNORE\s+)?INTO\s+`?tb_message_inbox`?\b',sql or '',re.I)
        and re.search(r"'activities'\s*,\s*"+str(event)+r"\s*\)",sql or '',re.I))


def validate_gap(v,oid,event):
    assert v['orderStatus']==4 and v['orders']==1,v
    assert v['releaseEvents']==v['closedEvents']==1 and v['releaseEventId']==event,v
    assert v['releaseDone']==v['releaseInbox']==0,v
    assert v['databaseRemaining']==int(v['redisRemaining'])==2,v
    assert v['releaseMarker']==v['qualificationOrderId']==oid and v['releaseMarkerTtl']==-1,v


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


def run(h,folder,port,run_id):
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
    cut=False;gate=None
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
        event=pending['releaseEventId']
        gate=InboxInsertGate(h,'p6gap_'+run_id,event)
        c['gateConnectionId']=gate.connection_id;save()
        ack.connect_broker(h,ip);cut=False
        c['brokerRestored']=ack.network_state(h,ack.BROKER)
        for key in ('containerId','pid','startedAt'):assert c['brokerRestored'][key]==c['brokerBefore'][key]
        until=time.monotonic()+110
        while time.monotonic()<until:
            blocked=gate.blocked(event)
            gap=state(h,oid)
            if blocked and gap['releaseMarker']==oid:break
            time.sleep(.25)
        else:raise AssertionError(('Release Lua/Inbox gap not observed',gap,blocked))
        validate_gap(gap,oid,event)
        c['blockedReleaseTransaction']=blocked[0]
        c['redisReleasedBeforeInboxCommit']=gap;save()
        h.command(['docker','kill','--signal','KILL',APP],text=True)
        c['applicationKilled']=r.inspect_app(h)
        assert c['applicationKilled']['exitCode']==137 and not c['applicationKilled']['oomKilled']
        gate.release();gate=None
        trx=blocked[0]['transactionId'];assert str(trx).isdigit()
        until=time.monotonic()+30
        while time.monotonic()<until:
            count=int(h.sql(f"SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_id={trx}"))
            if count==0:break
            time.sleep(.25)
        else:raise AssertionError('Release transaction still exists after process termination')
        assert r.inspect_app(h)['state']=='exited'
        c['originalTransactionGoneBeforeRestart']=True
        c['afterKill']=state(h,oid);validate_gap(c['afterKill'],oid,event);save()
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
        if gate is not None:gate.release()
        if cut:ack.connect_broker(h,ip)


def main():
    p=argparse.ArgumentParser();p.add_argument('--work-dir',type=Path,required=True)
    p.add_argument('--run-id',required=True);p.add_argument('--port',type=int,default=18092);a=p.parse_args()
    assert re.fullmatch('[a-f0-9]{32}',a.run_id)
    h=r.load_helpers();assert h.sql('SELECT DATABASE()')=='hmdp_optimization_test'
    assert r.pairs(h.redis('CONFIG','GET','appendfsync'))=={'appendfsync':'always'}
    report=dict(runId=a.run_id,generatedAt=r.now(),executionMode='packaged-jar-release-gap-crash',
      productionHooksAdded=False,manualBusinessRepairUsed=False,manualMessageReplayUsed=False,
      brokerProcessRestarted=False,databaseOrRedisCrashed=False,cases=[],passed=False)
    try:
        report['cases'].append(run(h,a.work_dir,a.port,a.run_id));report['passed']=True
    finally:r.write(a.work_dir/'application.json',report)

if __name__=='__main__':main()
