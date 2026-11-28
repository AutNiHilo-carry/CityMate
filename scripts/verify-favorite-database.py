"""Verify favorites against isolated Docker dependencies; never use the business database."""
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import uuid


ROOT = Path(__file__).resolve().parents[1]


def build_compose(root, run, external_cache):
    data = {
        'services': {
            'mysql': {
                'image': 'mysql:8.0',
                'command': '--default-authentication-plugin=mysql_native_password '
                           '--sql-mode=NO_ENGINE_SUBSTITUTION',
                'environment': {'MYSQL_ROOT_PASSWORD': 'isolation_test_only',
                                'MYSQL_DATABASE': 'hmdp_optimization_test'},
                'volumes': [f'{(root / "src/main/resources/db/hmdp.sql").as_posix()}:'
                            '/docker-entrypoint-initdb.d/01-base.sql:ro'],
                'healthcheck': {'test': ['CMD-SHELL',
                                        'mysqladmin ping -h 127.0.0.1 -pisolation_test_only --silent'],
                                'interval': '2s', 'timeout': '2s', 'retries': 60},
            },
            'redis': {
                'image': 'redis:7-alpine',
                'healthcheck': {'test': ['CMD', 'redis-cli', 'ping'],
                                'interval': '2s', 'timeout': '2s', 'retries': 30},
            },
            'memcached': {'image': 'memcached:1.6-alpine'},
            'test': {
                'image': 'maven:3.9-eclipse-temurin-8',
                'working_dir': '/work',
                'volumes': [f'{(root / "pom.xml").as_posix()}:/source/pom.xml:ro',
                            f'{(root / "src").as_posix()}:/source/src:ro',
                            f'{(root / "config").as_posix()}:/source/config:ro',
                            f'{run.as_posix()}:/evidence', 'maven-cache:/root/.m2'],
                'environment': {
                    'SPRING_DATASOURCE_URL': 'jdbc:mysql://mysql:3306/hmdp_optimization_test'
                                             '?useSSL=false&serverTimezone=UTC&characterEncoding=utf8',
                    'SPRING_DATASOURCE_USERNAME': 'root',
                    'SPRING_DATASOURCE_PASSWORD': 'isolation_test_only',
                    'SPRING_REDIS_HOST': 'redis', 'SPRING_REDIS_PORT': '6379',
                    'SPRING_REDIS_PASSWORD': '',
                    'HMDP_MEMCACHE_ENABLED': 'true', 'HMDP_MEMCACHE_HOST': 'memcached',
                    'HMDP_CDC_ENABLED': 'false', 'HMDP_MQ_ENABLED': 'false',
                    'HMDP_ANALYTICS_ENABLED': 'false', 'CITYMATE_AI_ENABLED': 'false',
                    'CITYMATE_RELIABILITY_SCHEDULING_ENABLED': 'false',
                },
                'command': ['sh', '-c',
                            'set -eu; mkdir -p /work/docs; '
                            'cp -a /source/pom.xml /source/config /source/src /work/; '
                            'mvn -B -Dtest=FavoriteMapperIntegrationTest '
                            '-Dcitymate.test.reports=/evidence/junit '
                            '-DargLine=-Xmx512m -Dspring.test.context.cache.maxSize=1 test'],
            },
        },
        'volumes': {'maven-cache': {'external': True, 'name': 'citymate_maven-cache'}
                    if external_cache else {}},
    }
    return data


def main():
    run_id = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:12]
    run = ROOT / 'target/favorite-database' / run_id
    run.mkdir(parents=True)
    spec = importlib.util.spec_from_file_location('ci_evidence', ROOT / 'scripts/run-ci.py')
    ci = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ci)
    source_hashes = ci.fingerprint(ROOT)
    manifest = {'runId': run_id, 'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                'status': 'STARTED', 'sourceSha256': source_hashes, 'commands': [],
                'database': 'hmdp_optimization_test', 'businessDatabaseUsed': False,
                'scope': 'FavoriteMapperIntegrationTest against disposable MySQL/Redis/Memcached',
                'sourceMountedReadOnly': True, 'liveModelUsed': False}
    project = 'citymate-favorite-' + uuid.uuid4().hex[:12]
    compose = []
    started = False
    code = 1

    def execute(argv, timeout=180, required=True):
        log = run / f'{len(manifest["commands"]) + 1:02d}.log'
        try:
            with log.open('w', encoding='utf-8') as output:
                result = subprocess.run(argv, cwd=ROOT, stdout=output,
                                        stderr=subprocess.STDOUT, timeout=timeout, check=False)
            status = result.returncode
        except subprocess.TimeoutExpired:
            status = 124
        manifest['commands'].append({'argv': argv, 'exitCode': status, 'log': log.name})
        if required and status:
            raise RuntimeError(f'Command failed ({status}); see {log.name}')
        return status, log

    try:
        manifest['gitHead'] = subprocess.check_output(
            ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        status, _ = execute(['docker', 'info', '--format', '{{.ServerVersion}}'],
                            timeout=20, required=False)
        if status:
            manifest['status'] = 'BLOCKED_DOCKER_ENGINE'
            code = 2
            raise RuntimeError('Docker engine unavailable; no database tests were executed')
        cached, _ = execute(['docker', 'volume', 'inspect', 'citymate_maven-cache',
                             '--format', '{{.Name}}'], required=False)
        config = run / 'compose.json'
        config.write_text(json.dumps(build_compose(ROOT, run, cached == 0), indent=2),
                          encoding='utf-8')
        env_file = run / 'empty.env'
        env_file.write_text('', encoding='utf-8')
        compose = ['docker', 'compose', '--env-file', str(env_file), '-p', project,
                   '-f', str(config)]
        status, log = execute(['docker', 'ps', '-aq', '--filter',
                               'label=com.docker.compose.project=' + project])
        if log.read_text(encoding='utf-8').strip():
            raise RuntimeError('Generated test project already exists; refusing to alter it')
        started = True
        execute(compose + ['up', '-d', '--wait', '--wait-timeout', '120',
                           'mysql', 'redis', 'memcached'], timeout=240)
        execute(compose + ['run', '--rm', '--no-deps', 'test'], timeout=600)
        suites = ci.junit_summary(run / 'junit')
        if ({s['name'] for s in suites} !=
                {'com.hmdp.reliability.FavoriteMapperIntegrationTest'}
                or any(s['failures'] or s['errors'] or s['skipped'] for s in suites)
                or sum(s['tests'] for s in suites) == 0):
            raise RuntimeError('Fresh database test reports failed validation')
        manifest['suites'] = suites
        manifest['status'] = 'PASSED'
        code = 0
    except Exception as failure:
        manifest['failure'] = str(failure)
        if manifest['status'] != 'BLOCKED_DOCKER_ENGINE':
            manifest['status'] = 'FAILED'
        print(str(failure), file=sys.stderr)
    finally:
        if started:
            execute(compose + ['logs', '--no-color', '--tail', '100', 'mysql', 'redis'],
                    timeout=20, required=False)
            status, _ = execute(compose + ['down', '-v', '--timeout', '10'],
                                timeout=90, required=False)
            manifest['isolatedProjectCleaned'] = status == 0
            if status:
                code = 1
                manifest['status'] = 'CLEANUP_FAILED'
        manifest['sourceUnchangedDuringChecks'] = source_hashes == ci.fingerprint(ROOT)
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
