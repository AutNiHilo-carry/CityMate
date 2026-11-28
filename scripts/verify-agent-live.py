"""Bounded live-model queries using an expiring synthetic, non-admin login.

Never reads or prints model credentials. No user, voucher, order or payment writes.
Only a randomly named Redis login fixture and its own user-limit key are removed.
"""
import datetime
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--coupon-shop-id', type=int)
OPTIONS = parser.parse_args()
BASE = 'http://127.0.0.1:8081'
RUN = uuid.uuid4().hex
FOLDER = ROOT / 'target/agent-live' / RUN
FOLDER.mkdir(parents=True, exist_ok=True)
TOKEN = secrets.token_hex(32)
USER_ID = str(8000000000000000 + secrets.randbelow(1000000000000000))
LOGIN_KEY = 'login:token:' + TOKEN
created = False
report = dict(runId=RUN, generatedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
              type='three selected live model queries, not a benchmark',
              credentialRead=False, syntheticLogin=True, loginFixtureRemoved=False,
              businessWrites=False, cases=[])


def redis_eval(script, keys, args):
    result = subprocess.run(['docker', 'exec', 'citymate-redis-1', 'redis-cli', '--raw',
                             'EVAL', script, str(len(keys)), *keys, *args],
                            capture_output=True, text=True, encoding='utf-8', timeout=10)
    if result.returncode:
        raise RuntimeError('Redis fixture operation failed')
    return result.stdout.strip()


def request(path, body=None, login=False):
    headers = {'Content-Type': 'application/json'}
    if login:
        headers['authorization'] = TOKEN
    payload = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = Request(BASE + path, data=payload, headers=headers)
    started = time.monotonic()
    try:
        with urlopen(req, timeout=35) as response:
            return response.status, json.load(response), round((time.monotonic() - started) * 1000)
    except HTTPError as error:
        return error.code, {}, round((time.monotonic() - started) * 1000)


def run_case(name, body):
    code, response, elapsed = request('/agent/query', body, True)
    data = response.get('data') or {}
    # This fixture only asks about public shop data and platform rules, no private orders.
    case = dict(name=name, request=body, http=code, elapsedMs=elapsed,
                status=data.get('status'), answered=data.get('answered'),
                failureReason=data.get('failureReason'),
                outputRepairAttempted=data.get('outputRepairAttempted'),
                answer=data.get('answer'), steps=data.get('steps', []),
                sources=[{k: row.get(k) for k in ('citation', 'sourceKey', 'version', 'title')}
                         for row in data.get('sources', [])],
                observations=data.get('observations', []))
    report['cases'].append(case)
    (FOLDER / 'result.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps({'case': name, 'status': case['status'], 'http': code,
                      'elapsedMs': elapsed, 'steps': case['steps']}, ensure_ascii=False), flush=True)
    return case


try:
    assert request('/actuator/health')[1].get('status') == 'UP', 'Local API not ready'
    if OPTIONS.coupon_shop_id:
        assert OPTIONS.coupon_shop_id > 0
        shop = request('/shop/' + str(OPTIONS.coupon_shop_id))[1].get('data')
        assert shop, 'No selected shop'
        report['type'] = 'one selected positive voucher live query, not a benchmark'
    else:
        catalog = request('/shop/catalog?' + urlencode(dict(keyword='海底捞', page=1, size=1, sort='score')))[1]
        rows = catalog.get('data') or []
        assert catalog.get('success') and rows, 'No shop fixture'
        shop = rows[0]
    vouchers = request('/voucher/list/' + str(shop['id']))[1]
    report['baseline'] = dict(shopId=str(shop['id']), shopName=shop['name'],
                              voucherIds=[str(row['id']) for row in vouchers.get('data', [])])
    if OPTIONS.coupon_shop_id:
        assert report['baseline']['voucherIds'], 'Selected shop has no positive voucher fixture'
    assert request('/knowledge/sources/platform-claim-progress')[1].get('success'), 'No rules fixture'
    created = redis_eval("if redis.call('EXISTS',KEYS[1])==1 then return 0 end "
                         "redis.call('HSET',KEYS[1],'id',ARGV[1],'nickName','citymate-live-probe') "
                         "redis.call('PEXPIRE',KEYS[1],300000) return 1", [LOGIN_KEY], [USER_ID]) == '1'
    assert created, 'Fixture collision'
    me = request('/user/me', login=True)[1]
    assert str((me.get('data') or {}).get('id')) == USER_ID, 'Fixture authentication failed'
    if OPTIONS.coupon_shop_id:
        run_case('positive_vouchers', dict(shopId=str(shop['id']),
                 query='查询当前选定店铺的优惠券，简要说明券价和可领状态。若为演示券请标明，不要领取或付款。'))
    else:
        run_case('shop_and_live_vouchers', dict(query='找一下海底捞火锅，看看有哪些优惠券。请实际查询商户和券。'))
        run_case('rag_claim_status', dict(query='秒杀返回订单号就算领取成功了吗？SUCCESS是不是表示已经付款？请查平台规则说明。'))
        run_case('missing_location', dict(query='帮我找附近人均50元以内的火锅店。'))
    for case in report['cases']:
        if case['name'] == 'shop_and_live_vouchers':
            tools = {step['tool'] for step in case['steps'] if step['status'] == 'OK'}
            case['automatedCheck'] = case['status'] == 'ANSWERED' and {'searchShops', 'listShopVouchers'} <= tools
        elif case['name'] == 'positive_vouchers':
            case['automatedCheck'] = case['status'] == 'ANSWERED' and any(
                row['tool'] == 'listShopVouchers' and row['result'].get('status') == 'OK'
                and bool(row['result'].get('data', {}).get('items')) for row in case['observations'])
        elif case['name'] == 'rag_claim_status':
            case['automatedCheck'] = case['status'] == 'ANSWERED' and bool(case['sources'])
        else:
            case['automatedCheck'] = case['status'] == 'NEEDS_CLARIFICATION' and not any(
                step['tool'] == 'searchShops' and step['status'] == 'OK' for step in case['steps'])
    report['allAutomatedChecksPassed'] = all(case['automatedCheck'] for case in report['cases'])
    report['semanticReview'] = 'PENDING'
finally:
    if created:
        report['loginFixtureRemoved'] = redis_eval(
            "if redis.call('HGET',KEYS[1],'id')~=ARGV[1] then return 0 end "
            "redis.call('DEL',KEYS[1],KEYS[2]) return 1",
            [LOGIN_KEY, 'limit:agent-query-user:' + USER_ID], [USER_ID]) == '1'
    jar = ROOT / 'target/citymate-0.0.1-SNAPSHOT.jar'
    if jar.exists():
        report['jarSha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
    (FOLDER / 'result.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print('LIVE_RUN=' + RUN, flush=True)
