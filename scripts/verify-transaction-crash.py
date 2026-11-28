"""Verify rollback after the real stock UPDATE and before the order INSERT commits."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time

PROJECT='citymate-tx-crash-p6'
APP=PROJECT+'-app'
SKU=980801

spec=importlib.util.spec_from_file_location('process_recovery',Path(__file__).with_name('verify-process-recovery.py'))
recovery=importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)
recovery.PROJECT=PROJECT
recovery.APP=APP


def array(value):
    return [] if value=='NULL' else (json.loads(value) or [])


def gate_statement(marker):
    assert re.fullmatch(r'[a-z0-9_]{1,64}',marker)
    return ("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION; "
        "SELECT id FROM tb_voucher_order WHERE id>=0 FOR UPDATE; "
        f"SELECT GET_LOCK('{marker}',0); DO SLEEP(240)")


class OrderInsertGapGate(recovery.DatabaseGate):
    def __init__(self,helper,marker):
        statement=gate_statement(marker)
        assert helper.sql('SELECT DATABASE()')=='hmdp_optimization_test'
        self.helper,self.marker,self.table=helper,marker,'tb_voucher_order'
        self.connection_id=None
        self.process=subprocess.Popen(['docker','exec',PROJECT+'-mysql-1','mysql','-uroot',
            '-pisolation_test_only','--batch','--raw','--skip-column-names',
            'hmdp_optimization_test','-e',statement],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        until=time.monotonic()+15
        while time.monotonic()<until:
            owner=helper.sql(f"SELECT IS_USED_LOCK('{marker}')")
            if owner.isdigit():
                self.connection_id=int(owner)
                return
            assert self.process.poll() is None,'Gap-lock holder exited before readiness'
            time.sleep(.2)
        self.release()
        raise AssertionError('Order insert gap lock was not acquired')

    def blocked_requests(self,order_id):
        assert str(order_id).isdigit()
        value=self.helper.sql(f"""SELECT JSON_ARRAYAGG(JSON_OBJECT(
          'transactionId',CAST(trx_id AS CHAR),'connectionId',trx_mysql_thread_id,
          'state',trx_state,'sql',LEFT(trx_query,1600),'rowsModified',trx_rows_modified,
          'rowsLocked',trx_rows_locked,'isolationLevel',trx_isolation_level))
          FROM information_schema.INNODB_TRX WHERE trx_state='LOCK WAIT'
          AND trx_query LIKE '%tb_voucher_order%' AND trx_mysql_thread_id<>{self.connection_id}""")
        rows=array(value)
        for row in rows:
            text=row['sql'] or ''
            row['containsOriginalOrderId']=str(order_id) in text
            row['isOrderInsert']=bool(re.match(r'\s*INSERT\s+INTO\s+`?tb_voucher_order`?\b',text,re.I))
        return rows


def dirty_stock(helper):
    # One external diagnostic session only. Application/global isolation is unchanged.
    value=helper.sql('SET SESSION TRANSACTION ISOLATION LEVEL READ UNCOMMITTED; '
        f'SELECT stock FROM tb_seckill_voucher WHERE voucher_id={SKU}')
    assert value.isdigit(),value
    return int(value)


def stock_locks(helper,transaction_id):
    assert str(transaction_id).isdigit()
    return array(helper.sql(f"""SELECT JSON_ARRAYAGG(JSON_OBJECT(
      'table',OBJECT_NAME,'index',INDEX_NAME,'type',LOCK_TYPE,'mode',LOCK_MODE,
      'status',LOCK_STATUS,'data',LOCK_DATA)) FROM performance_schema.data_locks
      WHERE ENGINE_TRANSACTION_ID={transaction_id}
      AND OBJECT_SCHEMA='hmdp_optimization_test' AND OBJECT_NAME='tb_seckill_voucher'"""))


def wait_rollback(helper,transaction_id):
    assert str(transaction_id).isdigit()
    until=time.monotonic()+30
    points=[]
    while time.monotonic()<until:
        remaining=int(helper.sql(f'SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_id={transaction_id}'))
        stock=dirty_stock(helper)
        points.append(dict(at=recovery.now(),originalTransactionCount=remaining,diagnosticStock=stock))
        if remaining==0 and stock==2:
            return points
        time.sleep(.25)
    raise AssertionError(('Original transaction did not roll back before restart',points))


def run_case(helper,folder,run_id,port):
    assert helper.sql('SELECT DATABASE()')=='hmdp_optimization_test'
    assert helper.sql(f'SELECT COUNT(*) FROM tb_voucher WHERE id={SKU}')=='0'
    helper.sql(f"""INSERT INTO tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,status)
      VALUES({SKU},1,'phase6 transaction crash','isolated fixture',0,100,1,1);
      INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time)
      VALUES({SKU},2,TIMESTAMPADD(HOUR,-1,NOW()),TIMESTAMPADD(DAY,1,NOW()));
      INSERT INTO tb_seckill_activity(voucher_id,total_stock) VALUES({SKU},2)""")
    helper.login_tokens(SKU,1)
    helper.publish(SKU,port)
    token='p6-'+str(SKU)+'-0'
    path=folder/(str(SKU)+'-evidence.json')
    case=dict(voucherId=SKU,startedAt=recovery.now(),initialStock=2,correct=False,
        gateMechanism='external RR primary-key range lock blocks INSERT but permits consistent SELECT',
        dirtyReadScope='one external diagnostic session only; no app/global isolation change')
    gate=OrderInsertGapGate(helper,'p6tx_'+run_id)
    try:
        case['gateConnectionId']=gate.connection_id
        before=recovery.inspect_app(helper)
        case['applicationBefore']=before
        reply=recovery.request(port,'POST','/voucher-order/seckill/'+str(SKU),token)
        order_id=str(reply['data']);assert order_id.isdigit()
        case['acceptedOrderId']=order_id
        case['acceptedAt']=recovery.now()
        record=recovery.stream_record(helper,order_id)
        case['originalStreamRecord']=record
        recovery.write(path,case)
        until=time.monotonic()+25
        while time.monotonic()<until:
            waiters=gate.blocked_requests(order_id)
            targets=[v for v in waiters if v['containsOriginalOrderId'] and v['isOrderInsert'] and v['rowsModified']>=1]
            if targets:
                break
            time.sleep(.2)
        else:
            raise AssertionError(('Stock-modifying transaction did not block on original order INSERT',waiters))
        target=targets[0]
        transaction_id=target['transactionId']
        assert target['connectionId']!=gate.connection_id
        case['blockedTransactionBeforeKill']=target
        case['stockLocksBeforeKill']=stock_locks(helper,transaction_id)
        assert any(v['type']=='RECORD' and v['status']=='GRANTED' and v['mode'].startswith('X') for v in case['stockLocksBeforeKill'])
        case['committedViewBeforeKill']=recovery.persisted(helper,SKU)
        case['dirtyStockBeforeKill']=dirty_stock(helper)
        frozen=case['committedViewBeforeKill']
        assert frozen['orders']==frozen['fulfillmentInbox']==0 and frozen['databaseRemaining']==2,frozen
        assert frozen['reservations']==frozen['fulfillments']==1,frozen
        assert case['dirtyStockBeforeKill']==1
        until=time.monotonic()+10
        while helper.redis('XPENDING','stream.orders','g1')[0]!=0 and time.monotonic()<until:
            time.sleep(.1)
        assert helper.redis('XPENDING','stream.orders','g1')[0]==0
        case['durableEventBeforeKill']=recovery.durable_event(helper,SKU)
        assert case['durableEventBeforeKill']['orderId']==order_id
        recovery.write(path,case)
        helper.command(['docker','kill','--signal=KILL',APP],text=True)
        stopped=recovery.inspect_app(helper)
        assert stopped['state']=='exited' and stopped['exitCode']==137 and not stopped['oomKilled']
        case['applicationKilled']=stopped
        recovery.write(path,case)
    finally:
        # Only the external blocker is killed, never the application database session.
        gate.release()
    rollback_began=time.monotonic()
    case['rollbackObservationsBeforeRestart']=wait_rollback(helper,transaction_id)
    case['rollbackObservationSeconds']=time.monotonic()-rollback_began
    case['persistedAfterRollbackBeforeRestart']=recovery.persisted(helper,SKU)
    rolled=case['persistedAfterRollbackBeforeRestart']
    assert rolled['orders']==rolled['fulfillmentInbox']==0 and rolled['databaseRemaining']==2,rolled
    assert rolled['reservations']==rolled['fulfillments']==1,rolled
    assert helper.redis('GET','seckill:stock:'+str(SKU))=='1'
    assert recovery.inspect_app(helper)['state']=='exited'
    case['rollbackVerifiedBeforeRestart']=True
    case['durableEventAfterRollback']=recovery.durable_event(helper,SKU)
    assert case['durableEventAfterRollback']['eventId']==case['durableEventBeforeKill']['eventId']
    recovery.write(path,case)
    began=time.monotonic()
    helper.command(['docker','start',APP],text=True)
    recovery.wait_healthy(port)
    resumed=recovery.inspect_app(helper)
    assert resumed['containerId']==before['containerId'] and resumed['imageId']==before['imageId']
    assert resumed['startedAt']!=before['startedAt'] and resumed['pid']>0
    case['applicationRestarted']=resumed
    recovery.write(path,case)
    case['recovered'],case['recoveryObservations']=recovery.wait_recovered(helper,SKU,order_id)
    case['recoveryObservationSeconds']=time.monotonic()-began
    case['eventAfterRecovery']=recovery.durable_event(helper,SKU)
    assert case['eventAfterRecovery']['state']=='DONE'
    assert case['eventAfterRecovery']['eventId']==case['durableEventBeforeKill']['eventId']
    case['duplicateChecks']=recovery.source_replay(helper,record,SKU,order_id,port,token)
    case['correct']=True
    case['completedAt']=recovery.now()
    recovery.write(path,case)
    print(json.dumps(dict(voucherId=SKU,correct=True,exitCode=137,dirtyStockBeforeKill=1,
        committedStockAfterRollback=2,finalStock=1,orders=1,
        recoveryObservationSeconds=case['recoveryObservationSeconds'])),flush=True)
    return case


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--work-dir',type=Path,required=True)
    parser.add_argument('--run-id',required=True)
    parser.add_argument('--port',type=int,default=18089)
    args=parser.parse_args()
    assert re.fullmatch(r'[a-f0-9]{32}',args.run_id)
    helper=recovery.load_helpers()
    assert helper.sql('SELECT DATABASE()')=='hmdp_optimization_test'
    assert recovery.pairs(helper.redis('CONFIG','GET','appendfsync'))=={'appendfsync':'always'}
    assert recovery.pairs(helper.redis('CONFIG','GET','appendonly'))=={'appendonly':'yes'}
    report=dict(runId=args.run_id,generatedAt=recovery.now(),executionMode='packaged-jar-transaction-recovery',
        productionHooksAdded=False,manualBusinessRepairUsed=False,applicationDbSessionKilled=False,
        databaseOrRedisCrashed=False,globalOrApplicationIsolationChanged=False,cases=[],passed=False)
    try:
        report['cases'].append(run_case(helper,args.work_dir,args.run_id,args.port))
        report['passed']=True
    finally:
        recovery.write(args.work_dir/'application.json',report)
    print('Actual stock-write transaction crash recovery passed: '+args.run_id,flush=True)


if __name__=='__main__':
    main()
