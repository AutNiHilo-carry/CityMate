"""Archive selected test metadata, never credentials, environment or application logs."""
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
run_id = sys.argv[1]
assert len(run_id) == 32 and all(c in '0123456789abcdef' for c in run_id)
run = root / 'target/logic-audit' / run_id
result = json.loads((run / 'result.json').read_text(encoding='utf-8-sig'))
assert result['exitCode'] == 0
log_bytes = (run / 'maven.log').read_bytes()
log = log_bytes.decode('utf-16' if log_bytes.startswith(b'\xff\xfe') else 'utf-8', errors='replace')
assert 'BUILD SUCCESS' in log
archive = root / 'docs/agent-verifications/2026-11-09' / run_id
archive.mkdir(parents=True, exist_ok=True)
cases = {}
suites = []
for report in sorted((run / 'reports').glob('TEST-*.xml')):
    suite = ET.parse(report).getroot()
    assert all(int(suite.attrib.get(name, 0)) == 0 for name in ('failures', 'errors', 'skipped'))
    clean = ET.Element('testsuite', {k: v for k, v in suite.attrib.items()
                                  if k in ('name', 'tests', 'failures', 'errors', 'skipped', 'time')})
    for case in suite.findall('testcase'):
        assert not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped'))
        key = (case.attrib['classname'], case.attrib['name'])
        assert key not in cases
        cases[key] = True
        ET.SubElement(clean, 'testcase', {k: v for k, v in case.attrib.items()
                                       if k in ('classname', 'name', 'time')})
    ET.ElementTree(clean).write(archive / report.name, encoding='utf-8', xml_declaration=True)
    suites.append(dict(name=suite.attrib['name'], tests=int(suite.attrib['tests'])))
agent_names = {'com.hmdp.knowledge.ReadOnlyAgentServiceTest',
               'com.hmdp.agent.tool.ReadOnlyAgentToolsTest',
               'com.hmdp.knowledge.AgentQueryIntegrationTest'}
agent_count = sum(1 for cls, name in cases if cls in agent_names)
assert agent_count == 22
assert ('com.hmdp.knowledge.DeepSeekClientTest',
        'nativeToolTurnPreservesToolReplyIdsAndDisablesThinking') in cases
paths = [
    'src/main/java/com/hmdp/knowledge/DeepSeekClient.java',
    'src/main/java/com/hmdp/service/IReadOnlyAgentService.java',
    'src/main/java/com/hmdp/service/impl/ReadOnlyAgentService.java',
    'src/main/java/com/hmdp/agent/tool/ReadOnlyAgentTools.java',
    'src/main/java/com/hmdp/controller/AgentQueryController.java',
    'src/main/java/com/hmdp/dto/AgentQueryRequest.java',
    'src/main/resources/agent-readonly-tools.json',
    'frontend/scripts/assistant.js', 'frontend/scripts/assistant-helpers.js',
    'frontend/assets/assistant.css', 'frontend/tests/assistant.test.js',
]
metadata = dict(
    date='2026-11-09', stage='PHASE7_READ_ONLY_AGENT', runId=run_id,
    backend=dict(tests=len(cases), failures=0, errors=0, skipped=0,
                 newAgentAndTransportTests=agent_count + 1, checkstyle='passed',
                 realMySqlRedis=True, model='mock and loopback HTTP server', suites=suites),
    frontend=dict(tests=21, failures=0, syntaxCheck='passed',
                  vue2TemplateCompile='passed', browserVisualCheck=False),
    liveModelCalledThisStage=False, paidModelQualityEvaluation=False,
    writesOrClaimsByAgent=False, newLoadTestMetrics=False,
    sourceHashes={path: hashlib.sha256((root / path).read_bytes()).hexdigest() for path in paths},
    sanitizedReports=archive.relative_to(root).as_posix(),
    deployment=dict(status='PENDING'),
    failedAttemptsNotCounted=['5cf93f66e0404b3bbc2bce99d7e6d039',
                             '0ee4c2e2e2b643298da90cb701d786c2',
                             '30c355c370364cf69de3946ba6cce2ab'])
target = root / 'docs/phase7-agent-verification.json'
target.write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
print(json.dumps({'tests': len(cases), 'newTests': agent_count + 1,
                  'frontend': 21, 'runId': run_id}))
