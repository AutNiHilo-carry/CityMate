"""Controlled SIGKILL/restart experiments against an isolated, unmodified executable JAR."""
import argparse
import datetime
import http.client
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time

PROJECT = 'citymate-crash-p6'
APP = PROJECT+'-app'
TABLES = {'tb_seckill_reservation', 'tb_voucher_order'}


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def load_helpers():
    spec=importlib.util.spec_from_file_location('jar_fixtures',Path(__file__).with_name('profile-packaged-transaction.py'))
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.PROJECT=PROJECT
    module.APP=APP
    return module


def write(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def pairs(value):
    return value if isinstance(value,dict) else dict(zip(value[::2],value[1::2]))


def inspect_app(helper):
    raw=json.loads(helper.command(['docker','inspect',APP],text=True).stdout)[0]
    assert raw['Path']=='java' and '-jar' in raw['Args']
    assert not any(m['Type']=='bind' for m in raw['Mounts'])
    assert raw['HostConfig']['RestartPolicy']['Name'] in ('','no')
    return dict(containerId=raw['Id'],imageId=raw['Image'],state=raw['State']['Status'],
        pid=raw['State']['Pid'],exitCode=raw['State']['ExitCode'],oomKilled=raw['State']['OOMKilled'],
        startedAt=raw['State']['StartedAt'],finishedAt=raw['State']['FinishedAt'])


def request(port,method,path,token):
    connection=http.client.HTTPConnection('127.0.0.1',port,timeout=15)
    try:
        connection.request(method,path,headers={'authorization':token})
        response=connection.getresponse()
        data=json.loads(response.read())
        assert response.status==200 and data.get('success'),(response.status,data)
        return data
    finally:
        connection.close()


def wait_healthy(port):
    until=time.monotonic()+120
    while time.monotonic()<until:
        connection=http.client.HTTPConnection('127.0.0.1',port,timeout=3)
        try:
            connection.request('GET','/actuator/health')
            response=connection.getresponse()
            if response.status==200 and json.loads(response.read()).get('status')=='UP':
                return
        except (OSError,ValueError):
            pass
        finally:
            connection.close()
        time.sleep(1)
    raise AssertionError('Restarted JAR did not become healthy')


class DatabaseGate:
    def __init__(self,helper,table,marker):
        assert table in TABLES and re.fullmatch(r'[a-z0-9_]{1,64}',marker)
        assert helper.sql('SELECT DATABASE()')=='hmdp_optimization_test'
        self.helper,self.table,self.marker=helper,table,marker
        self.connection_id=None
        # The table is locked before publishing the named readiness marker.
        statement=f"LOCK TABLES {table} WRITE; SELECT GET_LOCK('{marker}',0); DO SLEEP(240)"
        self.process=subprocess.Popen(['docker','exec',PROJECT+'-mysql-1','mysql','-uroot',
            '-pisolation_test_only','--batch','--raw','--skip-column-names',
            'hmdp_optimization_test','-e',statement],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        until=time.monotonic()+15
        while time.monotonic()<until:
            owner=helper.sql(f"SELECT IS_USED_LOCK('{marker}')")
            if owner.isdigit():
                self.connection_id=int(owner)
                return
            assert self.process.poll() is None,'Database gate process exited'
            time.sleep(.2)
        self.release()
        raise AssertionError('Database gate was not acquired')

    def blocked_requests(self,order_id):
        assert str(order_id).isdigit()
        value=self.helper.sql(f"""SELECT JSON_ARRAYAGG(JSON_OBJECT('connectionId',ID,'state',STATE,
          'sql',LEFT(INFO,500))) FROM information_schema.PROCESSLIST
          WHERE DB='hmdp_optimization_test' AND STATE LIKE '%lock%'
          AND INFO LIKE '%{self.table}%' AND ID<>{self.connection_id}""")
        rows=[] if value=='NULL' else (json.loads(value) or [])
        for row in rows:
            row['containsOriginalOrderId']=str(order_id) in row['sql']
        return rows

    def release(self):
        owner=self.helper.sql(f"SELECT IS_USED_LOCK('{self.marker}')")
        if self.connection_id is not None and owner==str(self.connection_id):
            # Kill only the known external blocker session, never application sessions.
            self.helper.sql(f'KILL {self.connection_id}')
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            self.process.wait(timeout=10)
        assert self.helper.sql(f"SELECT IS_USED_LOCK('{self.marker}')")=='NULL'


def persisted(helper,sku):
    return json.loads(helper.sql(f"""SELECT JSON_OBJECT(
      'reservations',(SELECT COUNT(*) FROM tb_seckill_reservation WHERE voucher_id={sku}),
      'orders',(SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id={sku}),
      'fulfillments',(SELECT COUNT(*) FROM tb_business_outbox b JOIN tb_seckill_reservation r ON r.order_id=b.aggregate_id WHERE r.voucher_id={sku} AND b.kind='SECKILL_FULFILL'),
      'fulfillmentInbox',(SELECT COUNT(*) FROM tb_message_inbox i JOIN tb_business_outbox b ON b.id=i.event_id JOIN tb_seckill_reservation r ON r.order_id=b.aggregate_id WHERE r.voucher_id={sku} AND i.consumer_group='fulfillment' AND b.kind='SECKILL_FULFILL'),
      'databaseRemaining',(SELECT stock FROM tb_seckill_voucher WHERE voucher_id={sku}))"""))


def durable_event(helper,sku):
    return json.loads(helper.sql(f"""SELECT JSON_OBJECT('eventId',b.id,'state',b.state,
        'leaseOwner',b.lease_owner,'leaseUntil',b.lease_until,'orderId',CAST(b.aggregate_id AS CHAR))
      FROM tb_business_outbox b JOIN tb_seckill_reservation r ON r.order_id=b.aggregate_id
      WHERE r.voucher_id={sku} AND b.kind='SECKILL_FULFILL'"""))


def stream_record(helper,order_id):
    matches=[]
    for record_id,fields in helper.redis('XRANGE','stream.orders','-','+'):
        fields=pairs(fields)
        if fields.get('id')==str(order_id):
            matches.append(dict(recordId=record_id,fields=fields))
    assert len(matches)==1,matches
    return matches[0]


def wait_recovered(helper,sku,order_id):
    until=time.monotonic()+180
    points=[]
    while time.monotonic()<until:
        value=helper.state(sku)
        points.append(dict(at=now(),orders=value['orders'],reservations=value['successfulReservations'],
            inbox=value['fulfillmentInbox'],unfinished=value['unfinishedFulfillmentEvents'],
            pending=value['streamPendingGlobal']))
        if (value['orders']==value['successfulReservations']==value['fulfillmentInbox']==1
            and value['unfinishedFulfillmentEvents']==value['streamPendingGlobal']==0):
            assert set(value['persistedIds'])=={str(order_id)},value
            assert value['databaseRemaining']==int(value['redisRemaining'])==1,value
            assert value['duplicateOwners']==0,value
            return value,points
        time.sleep(.5)
    raise AssertionError(('Recovery did not drain within 180 seconds',points[-5:]))


def source_replay(helper,record,sku,order_id,port,token):
    ids=[]
    for i in range(2):
        args=[]
        for key,value in record['fields'].items():
            args.extend([key,value])
        ids.append(helper.redis('XADD','stream.orders','*',*args))
    until=time.monotonic()+30
    target=tuple(map(int,ids[-1].split('-')))
    while time.monotonic()<until:
        group=next(pairs(g) for g in helper.redis('XINFO','GROUPS','stream.orders') if pairs(g)['name']=='g1')
        seen=tuple(map(int,group['last-delivered-id'].split('-')))
        if seen>=target and helper.redis('XPENDING','stream.orders','g1')[0]==0:
            break
        time.sleep(.25)
    else:
        raise AssertionError('Replayed source records were not consumed and acknowledged')
    reply=request(port,'POST','/voucher-order/seckill/'+str(sku),token)
    assert str(reply['data'])==str(order_id),reply
    final=helper.state(sku)
    assert final['orders']==final['successfulReservations']==final['fulfillmentInbox']==1,final
    assert final['databaseRemaining']==int(final['redisRemaining'])==1,final
    assert final['duplicateOwners']==final['unfinishedFulfillmentEvents']==final['streamPendingGlobal']==0,final
    status=request(port,'GET','/voucher-order/requests/'+str(order_id),token)
    assert status['data']['state']=='SUCCESS',status
    return dict(replayRecordIds=ids,httpRetryReturnedOriginalOrderId=True,
        ownedStatus=status['data'],sourceRecordsObservedAndAcked=True,final=final)


def run_case(helper,folder,run_id,sku,table,port):
    assert helper.sql('SELECT DATABASE()')=='hmdp_optimization_test'
    assert helper.sql(f'SELECT COUNT(*) FROM tb_voucher WHERE id={sku}')=='0'
    helper.sql(f"""INSERT INTO tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,status)
      VALUES({sku},1,'phase6 process recovery','isolated fixture',0,100,1,1);
      INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time)
      VALUES({sku},2,TIMESTAMPADD(HOUR,-1,NOW()),TIMESTAMPADD(DAY,1,NOW()));
      INSERT INTO tb_seckill_activity(voucher_id,total_stock) VALUES({sku},2)""")
    helper.login_tokens(sku,1)
    helper.publish(sku,port)
    token='p6-'+str(sku)+'-0'
    case=dict(voucherId=sku,gateTable=table,initialStock=2,startedAt=now(),correct=False)
    gate=DatabaseGate(helper,table,'p6crash_'+run_id+'_'+str(sku))
    killed=False
    try:
        case['gateConnectionId']=gate.connection_id
        before=inspect_app(helper)
        case['applicationBefore']=before
        reply=request(port,'POST','/voucher-order/seckill/'+str(sku),token)
        order_id=str(reply['data']); assert order_id.isdigit()
        case['acceptedOrderId']=order_id
        case['acceptedAt']=now()
        record=stream_record(helper,order_id)
        case['originalStreamRecord']=record
        until=time.monotonic()+25
        while time.monotonic()<until:
            waiters=gate.blocked_requests(order_id)
            if any(w['containsOriginalOrderId'] for w in waiters):
                break
            time.sleep(.2)
        else:
            raise AssertionError(('Original request was not observed waiting at selected gate',waiters))
        case['blockedSqlBeforeKill']=waiters
        pending=helper.redis('XPENDING','stream.orders','g1','-','+',20)
        case['pendingBeforeKill']=pending
        write(folder/(str(sku)+'-evidence.json'),case)
        if table=='tb_seckill_reservation':
            assert any(p[0]==record['recordId'] for p in pending),pending
            case['checkpoint']='Stream PEL contains original accepted request; reservation write blocked'
        else:
            until=time.monotonic()+10
            while helper.redis('XPENDING','stream.orders','g1')[0]!=0 and time.monotonic()<until:
                time.sleep(.1)
            assert helper.redis('XPENDING','stream.orders','g1')[0]==0
            case['durableEventBeforeKill']=durable_event(helper,sku)
            assert str(case['durableEventBeforeKill']['orderId'])==order_id
            case['checkpoint']='Original fulfillment waits on order table after durable Outbox handoff'
        helper.command(['docker','kill','--signal=KILL',APP],text=True)
        killed=True
        stopped=inspect_app(helper)
        assert stopped['state']=='exited' and stopped['exitCode']==137 and not stopped['oomKilled'],stopped
        case['applicationKilled']=stopped
        write(folder/(str(sku)+'-evidence.json'),case)
    finally:
        gate.release()
    assert killed
    # No application process can mutate these facts between releasing the gate and this read.
    case['persistedAfterKillBeforeRestart']=persisted(helper,sku)
    write(folder/(str(sku)+'-evidence.json'),case)
    frozen=case['persistedAfterKillBeforeRestart']
    assert frozen['orders']==frozen['fulfillmentInbox']==0 and frozen['databaseRemaining']==2,frozen
    assert helper.redis('GET','seckill:stock:'+str(sku))=='1'
    if table=='tb_seckill_reservation':
        assert frozen['reservations'] in (0,1) and frozen['fulfillments']==0,frozen
        case['reservationCommitAfterDisconnectUncertain']=frozen['reservations']==1
    else:
        assert frozen['reservations']==frozen['fulfillments']==1,frozen
        event=durable_event(helper,sku)
        assert event['state'] in ['SENDING','SENT'],event
        assert event['eventId']==case['durableEventBeforeKill']['eventId']
        case['durableEventAfterKill']=event
    began=time.monotonic()
    helper.command(['docker','start',APP],text=True)
    wait_healthy(port)
    resumed=inspect_app(helper)
    assert resumed['containerId']==before['containerId'] and resumed['imageId']==before['imageId']
    assert resumed['startedAt']!=before['startedAt'] and resumed['pid']>0
    case['applicationRestarted']=resumed
    write(folder/(str(sku)+'-evidence.json'),case)
    case['recovered'],case['recoveryObservations']=wait_recovered(helper,sku,order_id)
    case['recoveryObservationSeconds']=time.monotonic()-began
    event=durable_event(helper,sku)
    assert event['state']=='DONE'
    if table=='tb_voucher_order':
        assert event['eventId']==case['durableEventBeforeKill']['eventId']
    case['eventAfterRecovery']=event
    case['duplicateChecks']=source_replay(helper,record,sku,order_id,port,token)
    case['correct']=True
    case['completedAt']=now()
    write(folder/(str(sku)+'-evidence.json'),case)
    print(json.dumps(dict(voucherId=sku,correct=True,exitCode=137,
        recoveryObservationSeconds=case['recoveryObservationSeconds'],orders=1,remainingStock=1)),flush=True)
    return case


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--work-dir',type=Path,required=True)
    parser.add_argument('--run-id',required=True)
    parser.add_argument('--port',type=int,default=18088)
    args=parser.parse_args()
    assert re.fullmatch(r'[a-f0-9]{32}',args.run_id)
    helper=load_helpers()
    assert helper.sql('SELECT DATABASE()')=='hmdp_optimization_test'
    assert pairs(helper.redis('CONFIG','GET','appendfsync'))=={'appendfsync':'always'}
    assert pairs(helper.redis('CONFIG','GET','appendonly'))=={'appendonly':'yes'}
    report=dict(runId=args.run_id,generatedAt=now(),executionMode='packaged-jar-process-recovery',
        faultMechanism='external isolated table lock then docker kill --signal=KILL; same-container restart',
        productionHooksAdded=False,redisOrDatabaseCrashed=False,persistencePolicyChanged=False,
        cases=[],passed=False)
    try:
        for sku,table in [(980701,'tb_seckill_reservation'),(980702,'tb_voucher_order')]:
            report['cases'].append(run_case(helper,args.work_dir,args.run_id,sku,table,args.port))
        report['passed']=True
    finally:
        write(args.work_dir/'application.json',report)
    print('Real process recovery passed: '+args.run_id,flush=True)


if __name__=='__main__':
    main()
