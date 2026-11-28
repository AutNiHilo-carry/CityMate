"""Summarize opt-in request spans from a completed isolated profile archive."""
import argparse
import datetime
import hashlib
import json
import math
from pathlib import Path


def distribution(values):
    ordered = sorted(values)
    assert ordered
    return dict(mean=sum(ordered)/len(ordered),
                p50=ordered[math.ceil(len(ordered)*.50)-1],
                p99=ordered[math.ceil(len(ordered)*.99)-1])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    archive = args.archive.resolve()
    app_path = archive/'application.json'
    app = json.loads(app_path.read_text(encoding='utf-8'))
    assert app['passed'] and app['testOnlyRequestProbe']
    assert app['redisFsync'] == 'always' and app['redisAppendOnly'] == 'yes'
    required = ['authRead', 'authRefresh', 'userLimit', 'globalLimit',
                'orderId', 'admissionLua']
    rounds = []
    for source in app['rounds']:
        traces = source['serverRequestSpans']
        assert source['correct'] and len(traces) == source['requests']
        assert source['systemErrors'] == 0
        assert all(t['redisFailures'] == 0 for t in traces)
        assert all(all(k in t['stages'] for k in required) for t in traces)
        server_total = sum(t['serverFilterMs'] for t in traces)
        stage_names = sorted({k for t in traces for k in t['stages']})
        stages = {}
        for name in stage_names:
            values = [t['stages'].get(name, {}).get('nanos', 0)/1e6 for t in traces]
            stages[name] = dict(perRequestMs=distribution(values),
                calls=sum(t['stages'].get(name, {}).get('calls', 0) for t in traces),
                shareOfSummedServerTimePercent=sum(values)/server_total*100)
        residual = []
        for t in traces:
            remaining = t['serverFilterMs'] - sum(s['nanos']/1e6 for s in t['stages'].values())
            assert remaining >= -0.001, 'Overlapping spans or clock inconsistency'
            residual.append(max(0, remaining))
        rounds.append(dict(concurrency=source['concurrency'], requests=source['requests'],
            orders=source['orders'], serverFilterMs=distribution([t['serverFilterMs'] for t in traces]),
            httpP99Ms=source['allResponseP99Ms'], httpResponsesPerSecond=source['httpResponsesPerSecond'],
            residualServerMs=distribution(residual), stages=stages,
            scriptCacheMisses=sum(t['scriptCacheMisses'] for t in traces)))
    summary = dict(generatedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        runId=app['runId'], status='REQUEST_STAGE_PROFILE_PASSED',
        measuredRequests=sum(r['requests'] for r in rounds),
        measuredOrders=sum(r['orders'] for r in rounds),
        applicationSha256=hashlib.sha256(app_path.read_bytes()).hexdigest(),
        redisFsync=app['redisFsync'], rounds=rounds,
        productionCodeChanged=False, optimizationClaimed=False,
        limitations=['One finite batch per concurrency; not maximum capacity',
            'Test-only dynamic proxies add overhead; not an optimization comparison',
            'Redis spans include client and network waits; not Redis server CPU time',
            'Filter timing excludes connector queue and client transport',
            'No per-request client/server ID pairing; do not subtract unrelated percentiles',
            'Timer labels classify this specific endpoint, not arbitrary Redis workloads'])
    output = archive/'request-stage-summary.json'
    if output.exists():
        raise RuntimeError('Summary already exists; preserve archived evidence')
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
