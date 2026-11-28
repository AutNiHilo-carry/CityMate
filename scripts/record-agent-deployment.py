"""Attach sanitized local rollout evidence to the Agent stage report."""
import hashlib
import json
from pathlib import Path
import shutil
import sys

root = Path(__file__).resolve().parents[1]
folder = Path(sys.argv[1]).resolve()
assert folder.is_relative_to(root / 'target/local-releases')
stage_path = root / 'docs/phase7-agent-verification.json'
stage = json.loads(stage_path.read_text(encoding='utf-8'))
for path, digest in stage['sourceHashes'].items():
    assert hashlib.sha256((root / path).read_bytes()).hexdigest() == digest, path
deployment = json.loads((folder / 'deployment.json').read_text(encoding='utf-8-sig'))
artifact = json.loads((folder / 'artifact.json').read_text())
smoke = json.loads((folder / 'smoke.json').read_text())
agent_smoke = json.loads((folder / 'agent-smoke.json').read_text())
assert deployment['status'] == 'JAVA_UPDATED_HEALTHY'
assert deployment['databaseContainersPreserved']
assert artifact['jarSha256'] == deployment['installedJarSha256']
assert artifact['jarSha256'] == hashlib.sha256((root / 'target/citymate-0.0.1-SNAPSHOT.jar').read_bytes()).hexdigest()
assert 'agent-readonly-tools.json' in artifact['resources']
assert smoke['passed'] and agent_smoke['allPassed']
archive = root / 'docs/agent-releases' / deployment['runId']
archive.mkdir(parents=True, exist_ok=True)
for name in ('artifact.json', 'deployment.json', 'smoke.json', 'agent-smoke.json'):
    shutil.copyfile(folder / name, archive / name)
stage['deployment'] = dict(status='JAVA_UPDATED_HEALTHY', runId=deployment['runId'],
                           jarSha256=artifact['jarSha256'],
                           evidence=archive.relative_to(root).as_posix(),
                           existingReleaseSmokeChecks=len(smoke['checks']),
                           agentSmokeChecks=len(agent_smoke['checks']),
                           databaseContainersPreserved=True, previousJarRetained=True,
                           liveModelCalled=False, browserVisualInspection=False)
stage_path.write_text(json.dumps(stage, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
print(json.dumps(stage['deployment']))
