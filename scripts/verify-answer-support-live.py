"""Opt-in live review of eight synthetic answers using local Compose credentials, never printed."""
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid


ROOT = Path(__file__).resolve().parents[1]


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def live_plan(root, run, external_cache):
    favorite = load_module('favorite_plan', root / 'scripts/verify-favorite-database.py')
    plan = favorite.build_compose(root, run, external_cache)
    service = plan['services']['test']
    service['environment'] = {
        'CITYMATE_LIVE_REVIEW': 'true',
        'DEEPSEEK_API_KEY': '${DEEPSEEK_API_KEY:?Configure the local model key}',
        'DEEPSEEK_MODEL': '${DEEPSEEK_MODEL:-deepseek-flash}',
    }
    service['command'][-1] = service['command'][-1].replace(
        '-Dtest=FavoriteMapperIntegrationTest', '-Dtest=EvidenceSupportLiveEvaluation')
    return {'services': {'test': service}, 'volumes': plan['volumes']}


def main():
    run = ROOT / 'target/support-live' / (
        dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:12])
    run.mkdir(parents=True)
    ci = load_module('ci_evidence', ROOT / 'scripts/run-ci.py')
    before = ci.fingerprint(ROOT)
    manifest = {'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'status': 'STARTED',
                'sourceSha256': before, 'commands': [], 'maximumSyntheticReviews': 8,
                'credentialValuesArchived': False, 'businessServicesStarted': False}
    compose = []
    started = False
    code = 1

    def execute(argv, timeout=180, required=True):
        log = run / f'{len(manifest["commands"]) + 1:02d}.log'
        try:
            with log.open('w', encoding='utf-8') as output:
                process = subprocess.run(argv, cwd=ROOT, stdout=output,
                                         stderr=subprocess.STDOUT, timeout=timeout, check=False,
                                         env={k: v for k, v in os.environ.items() if k not in
                                              {'DEEPSEEK_API_KEY', 'DEEPSEEK_MODEL',
                                               'CITYMATE_AI_ENABLED'}})
            result = process.returncode
        except subprocess.TimeoutExpired:
            result = 124
        manifest['commands'].append({'argv': argv, 'exitCode': result, 'log': log.name})
        if required and result:
            raise RuntimeError(f'Command failed ({result}); see {log.name}')
        return result

    try:
        if not (ROOT / '.env').is_file():
            raise RuntimeError('Local .env is missing; no API request was made')
        manifest['gitHead'] = subprocess.check_output(
            ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        execute(['docker', 'info', '--format', '{{.ServerVersion}}'], timeout=20)
        cache = execute(['docker', 'volume', 'inspect', 'citymate_maven-cache'], required=False) == 0
        config = run / 'compose.json'
        config.write_text(json.dumps(live_plan(ROOT, run, cache), indent=2), encoding='utf-8')
        compose = ['docker', 'compose', '--env-file', str(ROOT / '.env'), '-p',
                   'citymate-review-' + uuid.uuid4().hex[:12], '-f', str(config)]
        started = True
        execute(compose + ['run', '--rm', '--no-deps', 'test'], timeout=240)
        suites = ci.junit_summary(run / 'junit')
        if ({s['name'] for s in suites} != {'com.hmdp.knowledge.EvidenceSupportLiveEvaluation'}
                or any(s['failures'] or s['errors'] or s['skipped'] for s in suites)):
            raise RuntimeError('Live evaluation suite did not complete successfully')
        manifest['suites'] = suites
        manifest['status'] = 'PASSED_SELECTED_SYNTHETIC_REVIEWS'
        code = 0
    except Exception as failure:
        manifest['status'] = 'FAILED'
        manifest['failure'] = str(failure)
        print(str(failure), file=sys.stderr)
    finally:
        if started:
            cleaned = execute(compose + ['down', '-v', '--timeout', '10'], required=False) == 0
            manifest['isolatedProjectCleaned'] = cleaned
            if not cleaned:
                code = 1
                manifest['status'] = 'CLEANUP_FAILED'
        manifest['sourceUnchangedDuringChecks'] = before == ci.fingerprint(ROOT)
        if not manifest['sourceUnchangedDuringChecks']:
            code = 1
            manifest['status'] = 'SOURCE_CHANGED'
        manifest['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        manifest['artifactsSha256'] = {
            p.relative_to(run).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(run.rglob('*')) if p.is_file()}
        (run / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
        print(manifest['status'], run / 'manifest.json')
    return code


if __name__ == '__main__':
    sys.exit(main())
