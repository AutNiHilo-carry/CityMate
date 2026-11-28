"""Exercise real local favorite persistence with two short-lived demo-only sessions."""
import concurrent.futures
import datetime as dt
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request
import uuid

BASE = 'http://127.0.0.1:8080/api'


def redis(*args):
    subprocess.run(['docker', 'exec', 'citymate-redis-1', 'redis-cli', *args],
                   check=True, capture_output=True)


def call(path, token=None, body=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['authorization'] = token
    req = urllib.request.Request(BASE + path, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 method='PUT' if body is not None else 'GET')
    with urllib.request.urlopen(req, timeout=20) as response:
        result = json.load(response)
    assert result.get('success'), result
    return result


def expect_status(path, status, token=None, body=None):
    try:
        call(path, token, body)
    except urllib.error.HTTPError as error:
        assert error.code == status, (path, error.code)
        return
    raise AssertionError('Expected HTTP ' + str(status))


def main():
    # Only use the reserved authors created by seed-demo-data.py, never a real user's session.
    query = "SELECT COUNT(*) FROM tb_user WHERE id IN (880001,880002) AND phone LIKE 'demo%';"
    result = subprocess.run(['docker', 'exec', '-i', 'citymate-mysql-1', 'sh', '-c',
                             'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --batch --skip-column-names hmdp'],
                            input=query.encode(), capture_output=True, check=True)
    assert result.stdout.strip() == b'2', 'Run seed-demo-data.py first; real users will not be used.'
    tokens = [uuid.uuid4().hex for _ in range(2)]
    originals = []
    checks = []
    try:
        for user, token in zip([880001, 880002], tokens):
            redis('HSET', 'login:token:' + token, 'id', str(user), 'nickName', 'favorite-verification')
            redis('EXPIRE', 'login:token:' + token, '600')
        a, b = tokens
        for token, kind in [(a, 'shop'), (a, 'note'), (b, 'shop')]:
            originals.append((token, kind, call('/favorites/' + kind + '/880001', token)['data']))
        expect_status('/favorites?kind=shop', 401)
        expect_status('/favorites/shop/880001', 401, body={'saved': True})
        checks.append('anonymous list and writes rejected')
        call('/favorites/shop/880001', b, {'saved': False})
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
            values = list(executor.map(lambda _: call('/favorites/shop/880001', a,
                          {'saved': True, 'userId': 880002})['data'], range(8)))
        assert all(value is True for value in values)
        first = [row for row in call('/favorites?kind=shop', a)['data'] if row['id'] == '880001']
        assert len(first) == 1 and first[0]['available'] is True
        call('/favorites/shop/880001', a, {'saved': True})
        again = [row for row in call('/favorites?kind=shop', a)['data'] if row['id'] == '880001']
        assert again[0]['savedAt'] == first[0]['savedAt']
        checks.append('eight concurrent saves produce one persistent row without changing save time')
        assert call('/favorites/shop/880001', b)['data'] is False
        assert not any(row['id'] == '880001' for row in call('/favorites?kind=shop&userId=880001', b)['data'])
        checks.append('forged owner fields do not expose or modify another account collection')
        call('/favorites/note/880001', a, {'saved': True})
        call('/favorites/shop/880001', a, {'saved': False})
        call('/favorites/shop/880001', a, {'saved': False})
        assert call('/favorites/shop/880001', a)['data'] is False
        assert call('/favorites/note/880001', a)['data'] is True
        assert any(row['id'] == '880001' for row in call('/favorites?kind=note', a)['data'])
        checks.append('idempotent removal and note/shop separation verified')
        expect_status('/favorites?kind=shop&page=0', 400, a)
        expect_status('/favorites/invalid/1', 400, a)
        expect_status('/favorites/shop/9999999999999', 404, a, {'saved': True})
        checks.append('invalid scopes pagination and missing targets are rejected')
    finally:
        try:
            for token, kind, saved in originals:
                call('/favorites/' + kind + '/880001', token, {'saved': saved})
        finally:
            for token in tokens:
                redis('DEL', 'login:token:' + token)
    report = {'checkedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'checks': checks,
              'failures': 0, 'demoUsersOnly': True, 'temporarySessionsDeleted': True,
              'initialSavedFlagsRestored': True, 'realFavoriteWrites': True,
              'ordersCreated': 0, 'modelCalls': 0}
    (Path(__file__).resolve().parents[1] / 'docs/favorites-live-verification.json').write_text(
        json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
