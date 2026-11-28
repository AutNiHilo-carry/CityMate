"""Observe committed business state with an uncommitted broker offset, then real replay."""
import argparse
import http.client
import importlib.util
import ipaddress
import json
from pathlib import Path
import re
import subprocess
import time

PROJECT='citymate-ack-p6'
APP=PROJECT+'-app'
BROKER=PROJECT+'-rocketmq-1'
MQ_NETWORK=PROJECT+'_mq'
GROUP=PROJECT+'-fulfillment'
SKU=980901

spec=importlib.util.spec_from_file_location('tx_recovery',Path(__file__).with_name('verify-transaction-crash.py'))
tx=importlib.util.module_from_spec(spec);spec.loader.exec_module(tx)
tx.PROJECT=PROJECT;tx.APP=APP;tx.SKU=SKU
tx.recovery.PROJECT=PROJECT;tx.recovery.APP=APP
recovery=tx.recovery


def parse_progress(text):
    rows=[]
    for line in text.splitlines():
        values=line.split()
        if len(values)>=7 and values[0]=='HmdpBusiness':
            rows.append(dict(topic=values[0],broker=values[1],queueId=int(values[2]),
                brokerOffset=int(values[3]),consumerOffset=int(values[4]),
                diff=int(values[5]),inflight=int(values[6])))
    assert len(rows)==4 and {v['queueId'] for v in rows}=={0,1,2,3},text
    return rows


def parse_offset_file(text,queue):
    normalized=False
    try:
        value=json.loads(text)
    except json.JSONDecodeError:
        # Fastjson can emit unquoted integer keys in this numeric-only offset map.
        text=re.sub(r'(?<=[{,])\s*(\d+)\s*:',r'"\1":',text)
        value=json.loads(text);normalized=True
    offsets=value.get('offsetTable',{}).get('HmdpBusiness@'+GROUP,{})
    return dict(selectedStoredOffset=offsets.get(str(queue)),numericKeysNormalized=normalized)


def progress(helper):
    result=helper.command(['docker','exec','-e','JAVA_OPT_EXT=-Xms32m -Xmx128m',
        BROKER,'sh','mqadmin','consumerProgress','-n','mq-nameserver:9876','-g',GROUP,'-t','HmdpBusiness'],text=True)
    return dict(at=recovery.now(),rows=parse_progress(result.stdout),raw=result.stdout)


def disk_offset(helper,queue):
    result=helper.command(['docker','exec',BROKER,'cat','/home/rocketmq/store/config/consumerOffset.json'],text=True)
    return dict(at=recovery.now(),raw=result.stdout,**parse_offset_file(result.stdout,queue))


def selected(snapshot,queue):
    return next(row for row in snapshot['rows'] if row['queueId']==queue)


def network_state(helper,name):
    assert name in [APP,BROKER]
    value=json.loads(helper.command(['docker','inspect',name],text=True).stdout)[0]
    assert value['Config']['Labels']['com.docker.compose.project']==PROJECT
    return dict(containerId=value['Id'],running=value['State']['Running'],pid=value['State']['Pid'],
        startedAt=value['State']['StartedAt'],networks={k:v['IPAddress'] for k,v in value['NetworkSettings']['Networks'].items()})


def tcp_probe(helper,ip):
    assert ipaddress.ip_address(ip).version==4
    result=subprocess.run(['docker','exec',APP,'bash','-c',
        'timeout 2 bash -c "exec 3<>/dev/tcp/'+ip+'/10911"'],capture_output=True,text=True,timeout=8)
    return dict(at=recovery.now(),returncode=result.returncode,stderr=result.stderr[-400:])


def connect_broker(helper,ip):
    assert ipaddress.ip_address(ip).version==4
    helper.command(['docker','network','connect','--ip',ip,'--alias','rocketmq',MQ_NETWORK,BROKER],text=True)


def duplicate_metric(port):
    connection=http.client.HTTPConnection('127.0.0.1',port,timeout=5)
    try:
        connection.request('GET','/actuator/prometheus',headers={'Authorization':'Bearer p6-profile-local-only'})
        response=connection.getresponse();body=response.read().decode('utf-8')
        assert response.status==200
        lines=[line for line in body.splitlines() if line.startswith('hmdp_mq_consumptions_total{')
            and 'group="fulfillment"' in line and 'result="duplicate"' in line]
        return dict(at=recovery.now(),count=sum(float(v.rsplit(' ',1)[1]) for v in lines),raw=lines)
    finally:
        connection.close()


def wait_committed(helper,order_id):
    until=time.monotonic()+30
    while time.monotonic()<until:
        value=helper.state(SKU)
        if value['orders']==value['successfulReservations']==value['fulfillmentInbox']==1 and value['unfinishedFulfillmentEvents']==0:
            assert set(value['persistedIds'])=={order_id}
            assert value['databaseRemaining']==int(value['redisRemaining'])==1
            return value
        time.sleep(.25)
    raise AssertionError(('Business commit not observed while MQ link was cut',value))


def run_case(helper,folder,run_id,port):
    path=folder/(str(SKU)+'-evidence.json')
    assert helper.sql('SELECT DATABASE()')=='hmdp_optimization_test'
    assert helper.sql(f'SELECT COUNT(*) FROM tb_voucher WHERE id={SKU}')=='0'
    helper.sql(f"""INSERT INTO tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,status)
      VALUES({SKU},1,'phase6 commit ack loss','isolated fixture',0,100,1,1);
      INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time)
      VALUES({SKU},2,TIMESTAMPADD(HOUR,-1,NOW()),TIMESTAMPADD(DAY,1,NOW()));
      INSERT INTO tb_seckill_activity(voucher_id,total_stock) VALUES({SKU},2)""")
    helper.login_tokens(SKU,1);helper.publish(SKU,port)
    token='p6-'+str(SKU)+'-0'
    case=dict(voucherId=SKU,initialStock=2,startedAt=recovery.now(),correct=False,
        faultScope='broker network disconnection before commit, then app SIGKILL after commit; broker process stays alive')
    network=json.loads(helper.command(['docker','network','inspect',MQ_NETWORK],text=True).stdout)[0]
    assert network['Labels']['com.docker.compose.project']==PROJECT
    case['appNetworkBefore']=network_state(helper,APP)
    case['brokerNetworkBefore']=network_state(helper,BROKER)
    assert set(case['brokerNetworkBefore']['networks'])=={MQ_NETWORK}
    assert MQ_NETWORK in case['appNetworkBefore']['networks'] and PROJECT+'_default' in case['appNetworkBefore']['networks']
    broker_ip=case['brokerNetworkBefore']['networks'][MQ_NETWORK]
    case['tcpProbeBefore']=tcp_probe(helper,broker_ip)
    assert case['tcpProbeBefore']['returncode']==0
    gate=None;cut=False
    try:
        gate=tx.OrderInsertGapGate(helper,'p6ack_'+run_id)
        case['gateConnectionId']=gate.connection_id
        case['applicationBefore']=recovery.inspect_app(helper)
        result=recovery.request(port,'POST','/voucher-order/seckill/'+str(SKU),token)
        order_id=str(result['data']);assert order_id.isdigit()
        case['acceptedOrderId']=order_id
        recovery.write(path,case)
        until=time.monotonic()+25
        while time.monotonic()<until:
            targets=[v for v in gate.blocked_requests(order_id) if v['isOrderInsert'] and v['containsOriginalOrderId'] and v['rowsModified']>=1]
            if targets:break
            time.sleep(.2)
        else:raise AssertionError('Original message was not delivered into the blocked transaction')
        case['blockedTransactionBeforeCut']=targets[0]
        assert tx.dirty_stock(helper)==1
        case['durableEventBeforeCut']=recovery.durable_event(helper,SKU)
        case['brokerProgressBeforeCut']=progress(helper)
        occupied=[v for v in case['brokerProgressBeforeCut']['rows'] if v['brokerOffset']>0]
        assert len(occupied)==1 and occupied[0]['brokerOffset']==1 and occupied[0]['consumerOffset']==0,occupied
        queue=occupied[0]['queueId'];case['originalQueueId']=queue
        case['brokerOffsetFileBeforeCut']=disk_offset(helper,queue)
        recovery.write(path,case)
        helper.command(['docker','network','disconnect',MQ_NETWORK,BROKER],text=True)
        cut=True
        case['brokerNetworkCut']=network_state(helper,BROKER)
        assert case['brokerNetworkCut']['running'] and MQ_NETWORK not in case['brokerNetworkCut']['networks']
        assert case['brokerNetworkCut']['pid']==case['brokerNetworkBefore']['pid']
        case['tcpProbeDuringCut']=tcp_probe(helper,broker_ip)
        assert case['tcpProbeDuringCut']['returncode'] in [1,124]
        assert recovery.persisted(helper,SKU)['orders']==0 and tx.dirty_stock(helper)==1
        recovery.write(path,case)
        # Complete the business transaction with MySQL/Redis reachable and broker unreachable.
        gate.release();gate=None
        case['committedWhileBrokerDisconnected']=wait_committed(helper,order_id)
        case['eventCommittedBeforeKill']=recovery.durable_event(helper,SKU)
        assert case['eventCommittedBeforeKill']['eventId']==case['durableEventBeforeCut']['eventId']
        assert case['eventCommittedBeforeKill']['state']=='DONE'
        case['duplicateMetricBeforeKill']=duplicate_metric(port)
        assert case['duplicateMetricBeforeKill']['count']==0
        time.sleep(6)
        case['brokerOffsetFileAfterCommit']=disk_offset(helper,queue)
        assert case['brokerOffsetFileAfterCommit']['selectedStoredOffset'] in (None,0)
        recovery.write(path,case)
        helper.command(['docker','kill','--signal=KILL',APP],text=True)
        case['applicationKilled']=recovery.inspect_app(helper)
        assert case['applicationKilled']['exitCode']==137 and case['applicationKilled']['state']=='exited' and not case['applicationKilled']['oomKilled']
        case['committedAfterKill']=helper.state(SKU)
        assert case['committedAfterKill']['orders']==case['committedAfterKill']['fulfillmentInbox']==1
        assert case['committedAfterKill']['databaseRemaining']==int(case['committedAfterKill']['redisRemaining'])==1
        recovery.write(path,case)
        connect_broker(helper,broker_ip);cut=False
        case['brokerNetworkRestored']=network_state(helper,BROKER)
        assert case['brokerNetworkRestored']['pid']==case['brokerNetworkBefore']['pid']
        assert case['brokerNetworkRestored']['startedAt']==case['brokerNetworkBefore']['startedAt']
        until=time.monotonic()+45
        while time.monotonic()<until:
            try:
                before_restart=progress(helper)
                break
            except (subprocess.SubprocessError,AssertionError):time.sleep(1)
        else:raise AssertionError('Cannot obtain live broker offset before app restart')
        case['liveBrokerProgressBeforeRestart']=before_restart
        original=selected(before_restart,queue)
        assert original['consumerOffset']==0 and original['brokerOffset']>=1,original
        assert recovery.inspect_app(helper)['state']=='exited'
        recovery.write(path,case)
        began=time.monotonic()
        helper.command(['docker','start',APP],text=True)
        recovery.wait_healthy(port)
        case['applicationRestarted']=recovery.inspect_app(helper)
        assert case['applicationRestarted']['containerId']==case['applicationBefore']['containerId']
        assert case['applicationRestarted']['imageId']==case['applicationBefore']['imageId']
        assert case['applicationRestarted']['startedAt']!=case['applicationBefore']['startedAt']
        until=time.monotonic()+180
        while time.monotonic()<until:
            metric=duplicate_metric(port)
            if metric['count']>=1:
                after_progress=progress(helper)
                if selected(after_progress,queue)['consumerOffset']>=1:break
            time.sleep(1)
        else:raise AssertionError('Automatic broker replay did not enter Inbox duplicate branch and commit offset')
        case['duplicateMetricAfterRestart']=metric
        case['liveBrokerProgressAfterReplay']=after_progress
        case['replayObservationSeconds']=time.monotonic()-began
        case['final']=helper.state(SKU)
        final=case['final']
        assert final['orders']==final['successfulReservations']==final['fulfillmentInbox']==1
        assert final['databaseRemaining']==int(final['redisRemaining'])==1
        assert final['duplicateOwners']==final['unfinishedFulfillmentEvents']==final['streamPendingGlobal']==0
        assert set(final['persistedIds'])=={order_id}
        assert recovery.durable_event(helper,SKU)['eventId']==case['durableEventBeforeCut']['eventId']
        retry=recovery.request(port,'POST','/voucher-order/seckill/'+str(SKU),token)
        assert str(retry['data'])==order_id
        case['httpRetryReturnedOriginalOrderId']=True
        case['correct']=True;case['completedAt']=recovery.now()
        recovery.write(path,case)
        print(json.dumps(dict(correct=True,exitCode=137,brokerOffsetBeforeRestart=0,
            brokerOffsetAfterReplay=selected(after_progress,queue)['consumerOffset'],
            inboxDuplicateCount=metric['count'],orders=1,remainingStock=1)),flush=True)
        return case
    finally:
        if gate is not None:gate.release()
        if cut:connect_broker(helper,broker_ip)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--work-dir',type=Path,required=True)
    parser.add_argument('--run-id',required=True)
    parser.add_argument('--port',type=int,default=18090)
    args=parser.parse_args()
    assert re.fullmatch(r'[a-f0-9]{32}',args.run_id)
    helper=recovery.load_helpers()
    assert helper.sql('SELECT DATABASE()')=='hmdp_optimization_test'
    assert recovery.pairs(helper.redis('CONFIG','GET','appendfsync'))=={'appendfsync':'always'}
    assert recovery.pairs(helper.redis('CONFIG','GET','appendonly'))=={'appendonly':'yes'}
    report=dict(runId=args.run_id,generatedAt=recovery.now(),executionMode='packaged-jar-commit-ack-loss',
        productionHooksAdded=False,manualSourceReplayUsed=False,brokerOffsetResetUsed=False,
        databaseOrRedisCrashed=False,brokerProcessRestarted=False,cases=[],passed=False)
    try:
        report['cases'].append(run_case(helper,args.work_dir,args.run_id,args.port))
        report['passed']=True
    finally:recovery.write(args.work_dir/'application.json',report)
    print('Committed transaction with lost broker progress verified: '+args.run_id,flush=True)


if __name__=='__main__':main()
