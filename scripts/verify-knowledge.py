"""Verify local knowledge publication and retrieval; retains a factual platform FAQ."""
import argparse
import datetime
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:8080')
    parser.add_argument('--admin-phone', required=True)
    parser.add_argument('--ordinary-phone', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    compose = ['docker', 'compose', '-p', 'citymate', '-f', str(root / 'dev/docker-compose.yml')]
    checks = []
    report = {'generatedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'scope': 'Local keyword retrieval only; no embeddings or generated answer',
              'checks': checks}

    def call(path, token=None, body=None, method=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['authorization'] = token
        request = urllib.request.Request(args.base + '/api' + path, headers=headers,
            data=None if body is None else json.dumps(body).encode(),
            method=method or ('POST' if body is not None else 'GET'))
        try:
            response = urllib.request.urlopen(request, timeout=10)
        except urllib.error.HTTPError as failure:
            response = failure
        return response.status, json.load(response)

    def check(name, condition):
        checks.append({'name': name, 'passed': bool(condition)})
        if not condition:
            raise AssertionError(name)

    def login(phone):
        status, code_result = call('/user/code?phone=' + phone, method='POST')
        if status != 200 or not code_result.get('success'):
            raise AssertionError('Local demo code request rejected')
        code = subprocess.check_output(compose + ['exec', '-T', 'redis', 'redis-cli', '--raw',
            'GET', 'login:code:' + phone], text=True).strip()
        status, result = call('/user/login', body={'phone': phone, 'code': code})
        if status != 200 or not result.get('success'):
            raise AssertionError('Local demo login rejected')
        return result['data']

    def search(query, **scope):
        status, result = call('/knowledge/search?' + urllib.parse.urlencode({'query': query, **scope}))
        if status != 200 or not result.get('success'):
            raise AssertionError('Public knowledge query rejected')
        return result['data']

    try:
        deadline = time.monotonic() + 180
        while True:
            try:
                status, result = call('/actuator/health')
                if status == 200 and result.get('status') == 'UP':
                    break
            except Exception:
                pass
            if time.monotonic() > deadline:
                raise AssertionError('Application health timeout')
            time.sleep(2)
        document = {'sourceKey': 'platform-claim-progress', 'expectedVersion': 0,
                    'title': '平台FAQ：领取进度与订单状态', 'kind': 'PLATFORM_FAQ',
                    'content': '领取进度需要查询本人请求。秒杀返回订单号表示已受理，SUCCESS表示正式订单已落库，不表示已经付款。'}
        check('anonymous_public_read', search('领取进度')['mode'] == 'KEYWORD_BM25_RETRIEVAL_ONLY')
        check('anonymous_publication_denied', call('/ops/knowledge', body=document)[0] == 401)
        ordinary = login(args.ordinary_phone)
        check('ordinary_publication_denied', call('/ops/knowledge', ordinary, document)[0] == 403)
        admin = login(args.admin_phone)
        previous = call('/knowledge/sources/' + document['sourceKey'])[1]
        if previous.get('success'):
            document['expectedVersion'] = previous['data']['version']
        published = call('/ops/knowledge', admin, document)[1]
        check('admin_publication', published.get('success') is True)
        version = published['data']['version']
        found = search('领取进度')
        row = next((r for r in found['evidence'] if r['sourceKey'] == document['sourceKey']), None)
        check('published_text_retrieved', row is not None and row['version'] == version)
        check('citation_points_to_current_source', call('/knowledge/sources/' + document['sourceKey']
            + '?version=' + str(version))[1].get('success') is True)
        document['expectedVersion'] = version
        document['content'] += ' REVIEW表示待核对，不能宣布领取成功。付费券还需核对付款状态。'
        updated = call('/ops/knowledge', admin, document)[1]
        check('version_update', updated.get('success') is True and updated['data']['version'] == version + 1)
        check('old_citation_rejected', call('/knowledge/sources/' + document['sourceKey']
            + '?version=' + str(version))[1].get('success') is False)
        check('stale_writer_rejected', call('/ops/knowledge', admin, document)[1].get('success') is False)
        rows = search('领取进度')['evidence']
        check('retrieval_only_returns_current_version', any(r['sourceKey'] == document['sourceKey']
            and r['version'] == version + 1 for r in rows) and not any(r['sourceKey'] == document['sourceKey']
            and r['version'] == version for r in rows))
        withdrawn = {'sourceKey': 'smoke-withdraw-' + str(time.time_ns()), 'expectedVersion': 0,
                     'kind': 'PLATFORM_FAQ', 'title': '撤回测试', 'content': '雪豹星河撤回验证原文'}
        check('withdraw_fixture_created', call('/ops/knowledge', admin, withdrawn)[1].get('success') is True)
        check('admin_withdrawal', call('/ops/knowledge/' + withdrawn['sourceKey']
            + '/withdraw?expectedVersion=1', admin, method='POST')[1].get('success') is True)
        withdrawn['expectedVersion'] = 1
        check('stale_publication_cannot_undo_withdrawal', call('/ops/knowledge', admin, withdrawn)[1].get('success') is False)
        check('withdrawn_source_rejected', call('/knowledge/sources/' + withdrawn['sourceKey'])[1].get('success') is False)
        check('withdrawn_evidence_excluded', not any(r['sourceKey'] == withdrawn['sourceKey']
            for r in search('雪豹星河撤回验证')['evidence']))
        check('no_evidence_means_no_match', search('彗星斑马烤鹅续费')['matched'] is False)
        check('oversized_top_k_rejected', call('/knowledge/search?query=FAQ&limit=6')[1].get('success') is False)
        report.update(passed=True, retainedSource=document['sourceKey'], retainedVersion=version + 1)
    except Exception as failure:
        report.update(passed=False, error=str(failure))
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
