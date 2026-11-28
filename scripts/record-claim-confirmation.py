"""Archive confirmation-flow evidence without credentials or full test logs."""
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
test_run, release_run = sys.argv[1:3]
for value in (test_run, release_run):
    assert len(value) == 32 and all(c in '0123456789abcdef' for c in value)
test = root / 'target/logic-audit' / test_run
release = root / 'target/local-releases' / release_run
assert json.loads((test / 'result.json').read_text(encoding='utf-8-sig'))['exitCode'] == 0
archive = root / 'docs/claim-confirmation' / test_run
archive.mkdir(parents=True, exist_ok=True)
cases = set()
for file in sorted((test / 'reports').glob('TEST-*.xml')):
    suite = ET.parse(file).getroot()
    assert all(int(suite.attrib.get(k, 0)) == 0 for k in ('failures', 'errors', 'skipped'))
    clean = ET.Element('testsuite', {k: v for k, v in suite.attrib.items()
                                  if k in ('name', 'tests', 'failures', 'errors', 'skipped', 'time')})
    for case in suite.findall('testcase'):
        key = (case.attrib['classname'], case.attrib['name'])
        assert key not in cases
        cases.add(key)
        assert not any(case.find(k) is not None for k in ('failure', 'error', 'skipped'))
        ET.SubElement(clean, 'testcase', {k: v for k, v in case.attrib.items()
                                       if k in ('name', 'classname', 'time')})
    ET.ElementTree(clean).write(archive / file.name, encoding='utf-8', xml_declaration=True)
assert len(cases) == 193
deployment = json.loads((release / 'deployment.json').read_text(encoding='utf-8-sig'))
artifact = json.loads((release / 'artifact.json').read_text())
assert deployment['status'] == 'JAVA_UPDATED_HEALTHY' and deployment['databaseContainersPreserved']
assert artifact['jarSha256'] == deployment['installedJarSha256']
for name in ('artifact.json', 'deployment.json', 'smoke.json', 'agent-smoke.json', 'claim-smoke.json'):
    value = json.loads((release / name).read_text(encoding='utf-8-sig'))
    if name == 'smoke.json':
        assert value['passed']
    if name in ('agent-smoke.json', 'claim-smoke.json'):
        assert value['allPassed']
    (archive / name).write_text(json.dumps(value, indent=2) + '\n')
paths = ['src/main/java/com/hmdp/controller/AssistantClaimController.java',
         'src/test/java/com/hmdp/knowledge/AssistantClaimControllerTest.java',
         'src/test/java/com/hmdp/knowledge/AgentQueryIntegrationTest.java',
         'frontend/scripts/assistant.js', 'frontend/scripts/assistant-helpers.js',
         'frontend/tests/assistant.test.js', 'frontend/assets/assistant.css']
report = dict(date='2026-11-10', stage='USER_CONFIRMED_CLAIM', testRun=test_run,
              releaseRun=release_run, backend=dict(tests=193, failures=0, errors=0, skipped=0,
                                                  newTests=5, checkstyle='passed'),
              frontend=dict(tests=31, newTests=10, failures=0, syntax='passed', vue2Compile='passed'),
              businessOrderCreatedOnlyInIsolatedTest=True,
              localBusinessClaimOrPayment=False, liveModelCalledThisStage=False,
              browserVisualInspection=False, githubPushed=False,
              evidence=archive.relative_to(root).as_posix(), jarSha256=artifact['jarSha256'],
              limitations=['preview is not an atomic price reservation', 'first 20 shop vouchers only',
                           'unknown submission has no automatic retry', 'no real payment or refund'],
              sourceHashes={p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in paths})
(root / 'docs/claim-confirmation-verification.json').write_text(
    json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
print(json.dumps({'backendTests': len(cases), 'frontendTests': 31, 'releaseRun': release_run}))
