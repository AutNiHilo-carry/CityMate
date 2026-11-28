"""Independent finite HTTP generator and read-only observers; all state is synthetic."""
import argparse, concurrent.futures, datetime, http.client, json, os, pathlib
import subprocess, threading, time, urllib.parse

def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def atomic(path, value):
    temporary=path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False),encoding='utf-8')
    os.replace(temporary,path)

def wait_json(path, timeout, run_id, app_container="citymate-profile-p6-app"):
    deadline=time.monotonic()+timeout
    last=time.monotonic()
    while time.monotonic()<deadline:
        if path.is_file():
            try:
                value=json.loads(path.read_text(encoding='utf-8'))
                if value.get('runId',run_id)!=run_id: raise RuntimeError('Run id mismatch')
                return value
            except json.JSONDecodeError: pass
        if time.monotonic()-last>30:
            print('Waiting for application: '+path.name,flush=True)
            state=subprocess.run(['docker','inspect','--format','{{.State.Running}}',
                                  app_container],capture_output=True,text=True,timeout=5)
            if state.returncode!=0 or state.stdout.strip()!='true':
                raise RuntimeError('Dedicated application stopped before exchange completion')
            last=time.monotonic()
        time.sleep(.1)
    raise TimeoutError(str(path))

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--work-dir',required=True)
    parser.add_argument('--run-id',required=True)
    parser.add_argument('--port',type=int,default=18086)
    parser.add_argument('--app-container',default='citymate-profile-p6-app')
    parser.add_argument('--compose-project',default='citymate-profile-p6')
    parser.add_argument('--packaged-jar',action='store_true')
    args=parser.parse_args()
    folder=pathlib.Path(args.work_dir)
    folder.mkdir(parents=True,exist_ok=True)
    samples=[]
    stopped=threading.Event()
    active={'voucher':None}
    prefixes=('process_cpu_usage','system_cpu_usage','jvm_memory_used_bytes',
              'jvm_gc_pause_seconds','hikaricp_connections_active','hikaricp_connections_pending',
              'hikaricp_connections_max','hmdp_seckill_admission_seconds','hmdp_seckill_consume_seconds',
              'hmdp_mq_send_seconds','hmdp_mq_consume_seconds','hmdp_outbox_')
    def observe():
        containers=[args.app_container]+[args.compose_project+'-'+service+'-1'
                    for service in ['mysql','redis','rocketmq']]
        while not stopped.is_set():
            point={'at':now(),'voucherId':active['voucher']}
            connection=http.client.HTTPConnection('127.0.0.1',args.port,timeout=4)
            try:
                connection.request('GET','/actuator/prometheus',headers={
                    'Authorization':'Bearer p6-profile-local-only'})
                response=connection.getresponse()
                body=response.read().decode('utf-8')
                if response.status!=200: raise RuntimeError('Metrics HTTP '+str(response.status))
                point['metrics']=[line for line in body.splitlines() if line.startswith(prefixes)]
            except Exception as e: point['metricsError']=type(e).__name__
            finally: connection.close()
            try:
                result=subprocess.run(['docker','stats','--no-stream','--format','{{json .}}',*containers],
                    capture_output=True,text=True,timeout=8,check=True)
                point['containers']=[json.loads(line) for line in result.stdout.splitlines() if line.strip()]
            except Exception as e: point['dockerStatsError']=type(e).__name__
            samples.append(point)
            stopped.wait(1)
    def request(sku,index,prefix):
        began=time.perf_counter()
        row={}
        connection=http.client.HTTPConnection('127.0.0.1',args.port,timeout=15)
        try:
            connection.request('POST','/voucher-order/seckill/'+str(sku),headers={
                'authorization':prefix+str(index)})
            response=connection.getresponse()
            row['httpStatus']=response.status
            value=json.loads(response.read().decode('utf-8'))
            if response.status==200 and value.get('success'):
                row.update(kind='accepted',orderId=str(value['data']))
            elif response.status==429: row['kind']='rate_limited'
            elif response.status==200 and value.get('errorMsg')=='库存不足': row['kind']='sold_out'
            else: row.update(kind='unexpected_response',error=value.get('errorMsg'))
        except Exception as e: row.update(kind='transport_error',errorType=type(e).__name__)
        finally:
            row['latencyMs']=(time.perf_counter()-began)*1000
            connection.close()
        return row
    thread=None
    try:
        for sku in range(970600,970604):
            ready=wait_json(folder/(str(sku)+'-ready.json'),600,args.run_id,args.app_container)
            active['voucher']=sku
            if thread is None:
                thread=threading.Thread(target=observe,daemon=True)
                thread.start()
            started=now()
            began=time.perf_counter()
            with concurrent.futures.ThreadPoolExecutor(max_workers=ready['concurrency']) as pool:
                rows=list(pool.map(lambda i:request(sku,i,ready['tokenPrefix']),range(ready['requests'])))
            elapsed=time.perf_counter()-began
            atomic(folder/(str(sku)+'-http.json'),{'runId':args.run_id,'startedAt':started,
                   'admissionSeconds':elapsed,'samples':rows})
            done=wait_json(folder/(str(sku)+'-done.json'),180,args.run_id,args.app_container)
            print(json.dumps({'voucherId':sku,'concurrency':ready['concurrency'],
                'orders':done['orders'],'correct':done['correct'],
                'httpResponsesPerSecond':done['httpResponsesPerSecond'],
                'allResponseP99Ms':done['allResponseP99Ms']},ensure_ascii=False),flush=True)
            if not done['correct']: raise RuntimeError('Reconciliation failed')
        wait_json(folder/'finished.json',180,args.run_id,args.app_container)
    finally:
        stopped.set()
        if thread is not None: thread.join(timeout=15)
        atomic(folder/'observations.json',{'runId':args.run_id,'generatedAt':now(),'samples':samples,
            'metricsScope':'Selected Prometheus series; observation adds load',
            'containerScope':('Packaged JAR process only; no Maven or test JVM' if args.packaged_jar
                              else 'Application container includes idle Maven parent and test JVM')})

if __name__=='__main__': main()
