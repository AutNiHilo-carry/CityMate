"""Record sanitized regression, rollout and selected live-query evidence."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
test_run, release_run, live_run = sys.argv[1:4]
positive_run = sys.argv[4] if len(sys.argv) > 4 else None
for value in (test_run, release_run, live_run, *([positive_run] if positive_run else [])):
    assert len(value) == 32 and all(c in '0123456789abcdef' for c in value)
test = root / 'target/logic-audit' / test_run
release = root / 'target/local-releases' / release_run
live = json.loads((root / 'target/agent-live' / live_run / 'result.json').read_text(encoding='utf-8'))
assert json.loads((test / 'result.json').read_text(encoding='utf-8-sig'))['exitCode'] == 0
assert live['loginFixtureRemoved']
assert live['allAutomatedChecksPassed']
positive = None
if positive_run:
    positive = json.loads((root / 'target/agent-live' / positive_run / 'result.json').read_text(encoding='utf-8'))
    assert positive['allAutomatedChecksPassed'] and positive['loginFixtureRemoved']
    assert positive['jarSha256'] == live['jarSha256']
deployment = json.loads((release / 'deployment.json').read_text(encoding='utf-8-sig'))
artifact = json.loads((release / 'artifact.json').read_text())
assert deployment['status'] == 'JAVA_UPDATED_HEALTHY' and deployment['databaseContainersPreserved']
assert live['jarSha256'] == artifact['jarSha256'] == deployment['installedJarSha256']
archive = root / 'docs/agent-live' / live_run
archive.mkdir(parents=True, exist_ok=True)
unique = set()
for file in sorted((test / 'reports').glob('TEST-*.xml')):
    original = ET.parse(file).getroot()
    assert all(int(original.attrib.get(k, 0)) == 0 for k in ('failures', 'errors', 'skipped'))
    clean = ET.Element('testsuite', {k: v for k, v in original.attrib.items()
                                  if k in ('name', 'tests', 'failures', 'errors', 'skipped', 'time')})
    for case in original.findall('testcase'):
        key = (case.attrib['classname'], case.attrib['name'])
        assert key not in unique
        unique.add(key)
        assert not any(case.find(k) is not None for k in ('failure', 'error', 'skipped'))
        ET.SubElement(clean, 'testcase', {k: v for k, v in case.attrib.items()
                                       if k in ('classname', 'name', 'time')})
    ET.ElementTree(clean).write(archive / file.name, encoding='utf-8', xml_declaration=True)
assert len(unique) == 188
for name in ('deployment.json', 'artifact.json', 'smoke.json', 'agent-smoke.json'):
    data = json.loads((release / name).read_text(encoding='utf-8-sig'))
    if name == 'smoke.json':
        assert data['passed']
    if name == 'agent-smoke.json':
        assert data['allPassed']
    (archive / name).write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
prior_runs = ['598bfc6712034f73a658c26ac6e32e34', '9383bea82e6146cab81d022371fbc76c',
              'c54d5f6f6fcf450689e45051ef537fdf']
request_count = len(live['cases']) + (len(positive['cases']) if positive else 0)
for previous_run in prior_runs:
    previous = json.loads((root / 'target/agent-live' / previous_run / 'result.json').read_text(encoding='utf-8'))
    request_count += len(previous['cases'])
for run_id in (*prior_runs, live_run, *([positive_run] if positive_run else [])):
    shutil.copyfile(root / 'target/agent-live' / run_id / 'result.json', archive / (run_id + '.json'))
paths = [
    'src/main/java/com/hmdp/agent/tool/ReadOnlyAgentTools.java',
    'src/main/java/com/hmdp/knowledge/DeepSeekClient.java',
    'src/main/java/com/hmdp/service/impl/ReadOnlyAgentService.java',
    'src/test/java/com/hmdp/agent/tool/ReadOnlyAgentToolsTest.java',
    'src/test/java/com/hmdp/knowledge/DeepSeekClientTest.java',
    'src/test/java/com/hmdp/knowledge/AgentQueryIntegrationTest.java',
    'src/test/java/com/hmdp/knowledge/ReadOnlyAgentServiceTest.java',
]
report = dict(date='2026-11-09', testRun=test_run, releaseRun=release_run, liveRun=live_run,
              backend=dict(tests=188, failures=0, errors=0, skipped=0, checkstyle='passed',
                           newTestsThisStage=4, model='mock and loopback HTTP in regression'),
              frontend=dict(changed=False, lastVerifiedTests=21, rerunThisStage=False),
              liveModelCalled=True, selectedTasks=3 + (1 if positive_run else 0),
              agentHttpRequestsThisStage=request_count, aggregateSuccessRateCalculated=False,
              positiveVoucherRun=positive_run,
              automatedChecksPassed=True, semanticReview='see accompanying markdown, not a benchmark',
              syntheticLoginsRemoved=True, businessWrites=False, browserVisualInspection=False,
              tokenCostsMeasured=False, newLoadMetrics=False, githubPushed=False,
              jarSha256=artifact['jarSha256'], evidence=archive.relative_to(root).as_posix(),
              firstLiveAttempt='598bfc6712034f73a658c26ac6e32e34',
              priorLiveRuns=prior_runs,
              firstAttemptIncludedInSuccessRate=False,
              sourceHashes={path: hashlib.sha256((root / path).read_bytes()).hexdigest() for path in paths})
(root / 'docs/phase7-agent-live-verification.json').write_text(
    json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
print(json.dumps({'backendTests': len(unique), 'liveRun': live_run,
                  'selectedTasks': report['selectedTasks']}))
