"""Public-page and anonymous API checks only; no login, model call or business writes."""
import json
from pathlib import Path
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen

root = Path(__file__).resolve().parents[1]
folder = Path(sys.argv[1]).resolve()
assert folder.is_relative_to(root / 'target/local-releases')
checks = []


def check(name, url, expected=200, body=None, contains=None):
    req = Request(url, data=body, headers={'Content-Type': 'application/json'})
    try:
        with urlopen(req, timeout=10) as response:
            status, content = response.status, response.read(2000000).decode('utf-8')
    except HTTPError as error:
        status, content = error.code, error.read(10000).decode('utf-8')
    passed = status == expected and (contains is None or contains in content)
    checks.append(dict(name=name, status=status, expected=expected, passed=passed))
    assert passed, name


check('java-health', 'http://127.0.0.1:8081/actuator/health', contains='UP')
check('assistant-page', 'http://127.0.0.1:8080/assistant.html')
check('agent-ui-module', 'http://127.0.0.1:8080/scripts/assistant.js', contains='/agent/query')
check('business-card-helper', 'http://127.0.0.1:8080/scripts/assistant-helpers.js', contains='observationCards')
check('anonymous-agent-api', 'http://127.0.0.1:8081/agent/query', 401, b'{"query":"catalog"}')
check('anonymous-agent-proxy', 'http://127.0.0.1:8080/api/agent/query', 401, b'{"query":"catalog"}')
report = dict(checks=checks, allPassed=True, realModelCalled=False,
              authenticatedBusinessWrites=False, browserVisualCheck=False)
(folder / 'agent-smoke.json').write_text(json.dumps(report, indent=2) + '\n')
print('Read-only Agent smoke: 6 checks passed; no login, model call or business writes.')
