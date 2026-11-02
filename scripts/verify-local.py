"""Verify a running local CityMate instance using real HTTP business operations.

Creates two demo accounts and three labelled vouchers; keeps them for inspection.
Never logs login tokens or codes. Requires Docker access to read demo login codes.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:8080')
    parser.add_argument('--project', default='hm-dianping')
    parser.add_argument('--admin-phone', help='Existing local demo administrator phone for repeat runs')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    compose = ['docker', 'compose', '-p', args.project, '-f', str(root / 'dev/docker-compose.yml')]
    checks = []
    report = {'generatedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
              'baseUrl': args.base, 'scope': 'Local HTTP smoke verification, not a load test',
              'checks': checks, 'objects': {}, 'retainedDemoData': True}

    def call(path, token=None, body=None, method=None):
        headers = {'Accept': 'application/json'}
        if token:
            headers['authorization'] = token
        data = None
        if body is not None:
            headers['Content-Type'] = 'application/json'
            data = json.dumps(body).encode()
        req = urllib.request.Request(args.base + path, headers=headers, data=data,
                                     method=method or ('POST' if data is not None else 'GET'))
        try:
            response = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        raw = response.read()
        return response.status, json.loads(raw) if raw else None

    def check(name, condition, **evidence):
        checks.append({'name': name, 'passed': bool(condition), **evidence})
        if not condition:
            raise AssertionError(name)

    def ok(path, token=None, body=None, method=None):
        status, result = call(path, token, body, method)
        if status != 200 or result.get('success') is not True:
            raise AssertionError(f'{path}: HTTP {status}, {result.get("errorMsg")}')
        return result

    def login(phone):
        ok('/api/user/code?phone=' + phone, method='POST')
        code = subprocess.check_output(compose + ['exec', '-T', 'redis', 'redis-cli', '--raw',
                                                   'GET', 'login:code:' + phone], text=True).strip()
        if not code.isdigit():
            raise AssertionError('Demo login code not available in Redis')
        token = ok('/api/user/login', body={'phone': phone, 'code': code})['data']
        user = ok('/api/user/me', token)['data']
        return token, user['id']

    try:
        with urllib.request.urlopen(args.base + '/', timeout=15) as response:
            check('frontend_home', response.status == 200 and b'id="app"' in response.read())
        with urllib.request.urlopen(args.base + '/imgs/blogs/blog1.jpg', timeout=15) as response:
            check('restored_image', response.status == 200 and response.headers['Content-Type'].startswith('image/'))
        status, health = call('/api/actuator/health')
        check('health_via_nginx', status == 200 and health.get('status') == 'UP')
        types = ok('/api/shop-type/list')['data']
        shops = ok('/api/shop/catalog?page=1&size=3&sort=score')['data']
        shop = shops[0]
        shop_id = shop['id']
        report['objects']['shopId'] = shop_id
        detail = ok('/api/shop/' + str(shop_id))['data']
        criteria = urllib.parse.urlencode({'typeId': detail['typeId'], 'maxPrice': detail.get('avgPrice') or 1000,
                                           'page': 1, 'size': 20, 'sort': 'price'})
        result = ok('/api/shop/search?' + criteria)['data']
        check('shop_search', bool(types) and any(s['id'] == shop_id for s in result))
        near = urllib.parse.urlencode({'typeId': detail['typeId'], 'x': detail['x'], 'y': detail['y'],
                                      'sort': 'distance', 'page': 1, 'size': 20})
        check('nearby_search', any(s['id'] == shop_id for s in ok('/api/shop/search?' + near)['data']))
        existing = ok('/api/voucher/list/' + str(shop_id))['data']
        check('voucher_query', isinstance(existing, list))
        status, _ = call('/api/voucher-order/mine')
        check('anonymous_order_denied', status == 401)

        stamp = str(time.time_ns())[-8:]
        token, user_id = login(args.admin_phone or '139' + stamp)
        other_token, other_id = login('138' + stamp)
        report['objects'].update(userId=user_id, otherUserId=other_id)
        check('real_code_login', user_id != other_id)
        capabilities = ok('/api/voucher/manage/capabilities', token)['data']
        check('demo_catalog_admin', capabilities.get('can_create_voucher') is True)
        common = {'shopId': shop_id, 'subTitle': 'Local startup verification',
                  'rules': 'Local demo only; no real payment or redemption.', 'actualValue': 1000}
        free_id = ok('/api/voucher', token, {**common, 'title': 'CityMate baseline free ' + stamp,
                                             'payValue': 0})['data']
        paid_id = ok('/api/voucher', token, {**common, 'title': 'CityMate baseline unpaid ' + stamp,
                                             'payValue': 500})['data']
        now = dt.datetime.fromisoformat(capabilities['server_time'])
        seckill_id = ok('/api/voucher/seckill', token, {**common,
            'title': 'CityMate baseline seckill ' + stamp, 'payValue': 0, 'stock': 1,
            'beginTime': (now - dt.timedelta(hours=1)).isoformat(timespec='seconds'),
            'endTime': (now + dt.timedelta(hours=1)).isoformat(timespec='seconds')})['data']
        report['objects'].update(freeVoucherId=free_id, paidVoucherId=paid_id, seckillVoucherId=seckill_id)
        order = ok('/api/voucher-order/ordinary/' + str(free_id), token, method='POST')['data']
        duplicate = ok('/api/voucher-order/ordinary/' + str(free_id), token, method='POST')['data']
        check('ordinary_idempotency', str(order) == str(duplicate), orderId=str(order))
        free = ok('/api/voucher-order/' + str(order), token)['data']
        check('free_voucher_held', free['status'] == 2)
        paid_order = ok('/api/voucher-order/ordinary/' + str(paid_id), token, method='POST')['data']
        check('paid_voucher_remains_unpaid', ok('/api/voucher-order/' + str(paid_order), token)['data']['status'] == 1)
        denied = call('/api/voucher-order/' + str(order), other_token)[1]
        check('cross_account_order_denied', denied.get('success') is False)
        check('own_order_list', any(str(o['order_id']) == str(order)
                                   for o in ok('/api/voucher-order/mine', token)['data']))

        # Wait for the actual activity publication, not just its metadata response.
        deadline = time.monotonic() + 45
        while True:
            status, published = call('/api/ops/seckill/' + str(seckill_id) + '/publish', token, method='POST')
            if status == 200 and published.get('success'):
                break
            if time.monotonic() > deadline:
                raise AssertionError('Seckill activity not published')
            time.sleep(1)
        admitted = ok('/api/voucher-order/seckill/' + str(seckill_id), token, method='POST')['data']
        check('seckill_accepted', bool(admitted), orderId=str(admitted))
        deadline = time.monotonic() + 60
        while True:
            state = ok('/api/voucher-order/requests/' + str(admitted), token)['data']['state']
            if state in ('SUCCESS', 'FAILED', 'REVIEW'):
                break
            if time.monotonic() > deadline:
                raise AssertionError('Seckill fulfillment timeout')
            time.sleep(1)
        check('seckill_fulfilled', state == 'SUCCESS', state=state)
        check('seckill_order_persisted', str(ok('/api/voucher-order/' + str(admitted), token)['data']['id']) == str(admitted))
        same = ok('/api/voucher-order/seckill/' + str(seckill_id), token, method='POST')['data']
        check('seckill_duplicate_returns_original', str(same) == str(admitted))
        status, exhausted = call('/api/voucher-order/seckill/' + str(seckill_id), other_token, method='POST')
        check('last_stock_not_oversold', status == 200 and exhausted.get('success') is False)
        status, private_request = call('/api/voucher-order/requests/' + str(admitted), other_token)
        check('cross_account_request_denied', private_request.get('success') is False)
        report['objects'].update(freeOrderId=str(order), unpaidOrderId=str(paid_order), seckillOrderId=str(admitted))
        check('inventory_reconciliation', ok('/api/ops/seckill/' + str(seckill_id) + '/reconcile', token)['data']['database_consistent'] is True)
        check('order_progress', bool(ok('/api/experience/orders/' + str(order), token)['data']))
        report['passed'] = True
    except Exception as error:
        report['passed'] = False
        report['error'] = str(error)
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
