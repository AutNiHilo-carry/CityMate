"""Run dependency-free checks and bind fresh reports to a source fingerprint.

No application startup, model API, Docker, .env loading or business data writes.
Spring-context/integration tests remain a separate, isolated verification stage.
"""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
CONTEXT_TEST = re.compile(
    r"@(?:SpringBootTest|WebMvcTest|DataJpaTest|ContextConfiguration|SpringJUnitConfig)\b"
    r"|\bextends\s+"
)


def select_tests(root):
    selected, excluded = [], []
    for path in sorted((root / 'src/test/java').rglob('*.java')):
        if not re.search(r'Tests?$', path.stem):
            continue
        text = path.read_text(encoding='utf-8-sig')
        if ('IntegrationTest' in path.stem or CONTEXT_TEST.search(text)
                or path.stem == 'NormalTest'):
            excluded.append(path.relative_to(root).as_posix())
            continue
        package = re.search(r'^package\s+([\w.]+);', text, re.M)
        if package is None:
            raise ValueError(f'Test has no package: {path.name}')
        selected.append(package[1] + '.' + path.stem)
    if not selected:
        raise ValueError('No dependency-free tests found')
    return selected, excluded


def fingerprint(root):
    paths = [root / 'pom.xml']
    for folder in ('src', 'config', 'scripts', 'frontend', '.github'):
        paths.extend(p for p in (root / folder).rglob('*')
                     if p.is_file() and p.suffix in
                     {'.java', '.xml', '.yaml', '.yml', '.py', '.ps1', '.js', '.cjs',
                      '.json', '.html', '.css', '.lua', '.sql', '.sh'}
                     and not {'node_modules', '__pycache__', 'target'}.intersection(p.parts))
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(paths) if p.is_file()}


def junit_summary(folder):
    reports = sorted(folder.glob('TEST-*.xml'))
    if not reports:
        raise ValueError('No fresh JUnit XML reports produced')
    suites = []
    for report in reports:
        suite = ET.parse(report).getroot()
        suites.append({'name': suite.attrib['name'], **{
            key: int(suite.attrib.get(key, 0))
            for key in ('tests', 'failures', 'errors', 'skipped')}})
    return suites


def main():
    run = ROOT / 'target/verification' / (
        dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:12])
    run.mkdir(parents=True)
    selected, excluded = select_tests(ROOT)
    before = fingerprint(ROOT)
    manifest = {
        'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
        'scope': 'Dependency-free backend tests, Checkstyle, frontend syntax and unit tests only',
        'status': 'RUNNING', 'selectedBackendClasses': selected,
        'excludedContextOrIntegrationTests': excluded, 'sourceSha256': before,
        'commands': [], 'liveModelUsed': False, 'businessServicesStarted': False,
    }
    exit_code = 1

    def execute(command, cwd=ROOT):
        index = len(manifest['commands']) + 1
        log = run / f'{index:02d}.log'
        with log.open('w', encoding='utf-8') as output:
            result = subprocess.run(command, cwd=cwd, stdout=output,
                                    stderr=subprocess.STDOUT, text=True, check=False)
        manifest['commands'].append({'argv': command, 'exitCode': result.returncode,
                                     'log': log.name})
        if result.returncode:
            print(log.read_text(encoding='utf-8', errors='replace')[-6000:])
            raise RuntimeError(f'Command failed; see {log}')

    try:
        manifest['gitHead'] = subprocess.check_output(
            ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        manifest['gitDirty'] = bool(subprocess.check_output(
            ['git', 'status', '--porcelain'], cwd=ROOT, text=True))
        maven = os.environ.get('CITYMATE_MAVEN') or shutil.which('mvn')
        node = shutil.which('node')
        if not maven or not node:
            raise RuntimeError('Maven and Node are required; CITYMATE_MAVEN can name mvn.cmd')
        execute([maven, '--version'])
        execute([node, '--version'])
        execute([sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts/tests'])
        maven_args = [maven, '-B', '-Dtest=' + ','.join(selected),
                      '-Dcitymate.test.reports=' + str(run / 'junit')]
        if os.environ.get('CITYMATE_MAVEN_REPO'):
            maven_args.append('-Dmaven.repo.local=' + os.environ['CITYMATE_MAVEN_REPO'])
        execute(maven_args + ['test'])
        suites = junit_summary(run / 'junit')
        actual = {suite['name'] for suite in suites}
        if actual != set(selected) or any(s['failures'] or s['errors'] for s in suites):
            raise RuntimeError('JUnit suite set or results do not match the requested selection')
        manifest['backendSuites'] = suites
        manifest['backendTests'] = sum(s['tests'] for s in suites)
        for script in sorted((ROOT / 'frontend/scripts').glob('*.js')):
            execute([node, '--check', str(script)])
        tests = sorted((ROOT / 'frontend/tests').glob('*.test.js'))
        if not tests:
            raise RuntimeError('No frontend tests found')
        execute([node, '--test', *map(str, tests)], ROOT / 'frontend')
        manifest['sourceUnchangedDuringChecks'] = before == fingerprint(ROOT)
        if not manifest['sourceUnchangedDuringChecks']:
            raise RuntimeError('Source changed during verification; rerun against a stable tree')
        manifest['status'] = 'PASSED'
        exit_code = 0
    except Exception as failure:
        manifest['status'] = 'FAILED'
        manifest['failure'] = str(failure)
        print(str(failure), file=sys.stderr)
    finally:
        manifest['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        manifest['artifactsSha256'] = {
            p.relative_to(run).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(run.rglob('*')) if p.is_file()}
        (run / 'manifest.json').write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(f'{manifest["status"]}: {run / "manifest.json"}')
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
