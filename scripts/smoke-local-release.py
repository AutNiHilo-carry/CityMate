"""Public reads and anonymous denials only; never creates users, coupons or orders."""
import http.client
import json
from pathlib import Path
import sys
import time

root = Path(__file__).resolve().parents[1]
folder = Path(sys.argv[1]).resolve()
assert folder.is_relative_to(root / 'target/local-releases')
deployment = json.loads((folder / 'deployment.json').read_text())
api_port = int(deployment['apiPort'])
web_port = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
checks = []


def request(port, path, method='GET', body=None):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=25)
    try:
        connection.request(method, path, body=body,
                           headers={'Content-Type': 'application/json'} if body else {})
        response = connection.getresponse()
        data = response.read()
        return response.status, data
    finally:
        connection.close()


def check(name, port, path, expected=200, method='GET', body=None, predicate=None):
    code, payload = request(port, path, method, body)
    passed = code == expected and (predicate is None or predicate(payload))
    checks.append(dict(name=name, path=path, port=port, method=method,
                       expectedStatus=expected, actualStatus=code, passed=passed))
    assert passed, (name, code)
    return payload


def success(payload):
    return json.loads(payload).get('success') is True


try:
    check('java health', api_port, '/actuator/health',
          predicate=lambda b: json.loads(b).get('status') == 'UP')
    check('public shop categories', api_port, '/shop-type/list', predicate=success)
    check('public shop catalog', api_port, '/shop/catalog?page=1&size=1&sort=score', predicate=success)
    for path in ('/', '/shop-list.html', '/experience.html', '/assistant.html'):
        check('web desktop route', web_port, path, predicate=lambda b: b'CityMate' in b)
    check('web JavaScript', web_port, '/scripts/app.js')
    check('nginx API proxy', web_port, '/api/shop-type/list', predicate=success)
    capabilities = json.loads(check('experience capabilities', api_port,
                                   '/experience/capabilities', predicate=success))['data']
    # Do not archive full capabilities or upstream/model configuration.
    flags = {k: capabilities[k] for k in ('enabled', 'mqEnabled', 'analyticsEnabled') if k in capabilities}
    for path in ('/voucher-order/mine', '/ops;v=1/outbox', '/actuator;v=1/prometheus'):
        check('anonymous protected route', api_port, path, expected=401)
    check('anonymous matrix shop write rejected', api_port, '/shop;v=1',
          expected=401, method='PUT', body='{"id":1}')
    check('anonymous AI request rejected without calling model', api_port,
          '/knowledge/answer', expected=401, method='POST', body='{"query":"rules"}')
    check('legacy pagination rejects overflow', api_port,
          '/shop/of/type?typeId=1&current=2147483647&x=120&y=30',
          predicate=lambda b: json.loads(b).get('success') is False)
    check('nginx monitoring guard', web_port, '/api/actuator;v=1/prometheus', expected=401)
    result = dict(runId=deployment['runId'],passed=True,checks=checks,capabilityFlags=flags,
                  apiPort=api_port,webPort=web_port,noAuthenticatedBusinessWrites=True,
                  liveModelCalled=False,browserVisualInspection=False,
                  note='Queries may produce normal analytics visit events; no claim or payment requests were sent.')
    print('Local release smoke checks passed:',len(checks))
finally:
    result = locals().get('result',dict(runId=deployment['runId'],passed=False,checks=checks))
    (folder / 'smoke.json').write_text(json.dumps(result,indent=2)+'\n')
