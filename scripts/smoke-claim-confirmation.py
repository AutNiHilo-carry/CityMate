"""Check public assets and anonymous preview denial, without claiming anything."""
import json
from pathlib import Path
import sys
import secrets
import subprocess
from urllib.error import HTTPError
from urllib.request import urlopen
from urllib.request import Request

root = Path(__file__).resolve().parents[1]
folder = Path(sys.argv[1]).resolve()
assert folder.is_relative_to(root / 'target/local-releases')
checks = []
for name, url, expected, marker in [
    ('claim-ui', 'http://127.0.0.1:8080/scripts/assistant.js', 200, 'confirmClaim'),
    ('claim-helper', 'http://127.0.0.1:8080/scripts/assistant-helpers.js', 200, 'claimFingerprint'),
    ('anonymous-preview', 'http://127.0.0.1:8081/assistant/claim-preview?shopId=4&voucherId=10', 401, None),
    ('anonymous-proxy-preview', 'http://127.0.0.1:8080/api/assistant/claim-preview?shopId=4&voucherId=10', 401, None),
]:
    try:
        with urlopen(url, timeout=10) as response:
            code, text = response.status, response.read(2000000).decode('utf-8')
    except HTTPError as error:
        code, text = error.code, ''
    passed = code == expected and (marker is None or marker in text)
    checks.append(dict(name=name, http=code, passed=passed))
    assert passed, name
fixture_removed = None
if '--authenticated-preview' in sys.argv[2:]:
    token = secrets.token_hex(32)
    user_id = str(8000000000000000 + secrets.randbelow(1000000000000000))
    key = 'login:token:' + token

    def redis_eval(script):
        result = subprocess.run(['docker', 'exec', 'citymate-redis-1', 'redis-cli', '--raw',
                                 'EVAL', script, '1', key, user_id], capture_output=True,
                                text=True, encoding='utf-8', timeout=10)
        assert result.returncode == 0, 'Redis fixture failed'
        return result.stdout.strip()

    created = redis_eval("if redis.call('EXISTS',KEYS[1])==1 then return 0 end "
                         "redis.call('HSET',KEYS[1],'id',ARGV[1]) "
                         "redis.call('PEXPIRE',KEYS[1],60000) return 1") == '1'
    assert created
    try:
        for shop, expected in [(4, True), (5, False)]:
            req = Request('http://127.0.0.1:8080/api/assistant/claim-preview?shopId='
                          + str(shop) + '&voucherId=10', headers={'authorization': token})
            with urlopen(req, timeout=15) as response:
                value = json.load(response)
                passed = value.get('success') is expected
                if expected:
                    data = value.get('data') or {}
                    passed = passed and data.get('shopId') == '4' and data.get('voucherId') == '10'
                    passed = passed and isinstance(data.get('payCents'), int)
                checks.append(dict(name='authenticated-preview' if expected else 'scope-mismatch-rejected',
                                   http=response.status, passed=passed))
                assert passed
    finally:
        fixture_removed = redis_eval("if redis.call('HGET',KEYS[1],'id')==ARGV[1] then "
                                     "return redis.call('DEL',KEYS[1]) end return 0") == '1'
        assert fixture_removed
(folder / 'claim-smoke.json').write_text(json.dumps(
    dict(allPassed=True, checks=checks, authenticatedPreview=fixture_removed is True,
         fixtureRemoved=fixture_removed, authenticatedClaim=False, realModelCalled=False), indent=2) + '\n')
print('Confirmation smoke:', len(checks), 'checks passed; no authenticated claim or payment.')
