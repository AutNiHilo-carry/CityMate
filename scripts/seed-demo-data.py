"""Append labelled synthetic catalog data to the local Docker demo; dry-run by default."""
import argparse
import datetime as dt
import html
import json
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CATEGORIES = [
    (1, '美食', ['桥边小火锅', '巷口家常菜', '湖畔早午餐', '街角热汤面'], [49, 38, 88, 22]),
    (2, 'KTV', ['橙声练歌房', '周末音乐空间'], [59, 128]),
    (3, '丽人美发', ['清风理发室', '午后造型屋'], [39, 98]),
    (4, '健身运动', ['运河羽毛球馆', '轻跑运动空间'], [35, 69]),
    (5, '按摩足疗', ['慢下来足浴', '肩颈放松小馆'], [79, 139]),
    (6, '美容SPA', ['叶间护理室', '晴日舒缓空间'], [99, 168]),
    (7, '亲子游乐', ['小树亲子工坊', '积木探险乐园'], [49, 89]),
    (8, '酒吧', ['落日无酒精酒馆', '河岸小酒馆'], [58, 98]),
    (9, '轰趴馆', ['朋友的客厅', '周末桌游屋'], [68, 108]),
    (10, '美睫美甲', ['指尖小花园', '橙光美甲室'], [59, 129]),
]
AREAS = ['武林商圈', '运河商圈', '西湖商圈', '滨江商圈']


def sql_value(value):
    if isinstance(value, (int, float)):
        return str(value)
    return "'" + str(value).replace('\\', '\\\\').replace("'", "''") + "'"


def mysql(sql):
    result = subprocess.run([
        'docker', 'exec', '-i', 'citymate-mysql-1', 'sh', '-c',
        'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names hmdp',
    ], input=sql.encode('utf-8'), capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', errors='replace'))
    return result.stdout.decode('utf-8').strip()


def insert_missing(table, columns, values):
    return ('INSERT INTO ' + table + '(' + ','.join(columns) + ') SELECT '
            + ','.join(sql_value(v) for v in values)
            + ' WHERE NOT EXISTS (SELECT 1 FROM ' + table + ' WHERE id=' + str(values[0]) + ');')


def build():
    rows = []
    authors = [(880001 + i, 'demo088000' + str(i + 1), '【演示】' + name)
               for i, name in enumerate(['周末散步员', '小巷发现家', '城市慢游记'])]
    for uid, phone, name in authors:
        rows.append(('tb_user', ['id', 'phone', 'nick_name', 'icon'],
                     [uid, phone, name, '/assets/mascots/xiaocheng-v1.png']))
    shops = []
    for type_id, category, names, prices in CATEGORIES:
        for name, price in zip(names, prices):
            i = len(shops)
            sid = 880001 + i
            area = AREAS[i % len(AREAS)]
            cover = '/assets/demo/category-' + str(type_id) + '.svg'
            # Fictional locations approximate Hangzhou neighborhoods, not actual business addresses.
            x, y = [(120.164, 30.274), (120.141, 30.316), (120.139, 30.250), (120.200, 30.210)][i % 4]
            title = '【演示】' + name
            shops.append({'id': sid, 'name': title, 'typeId': type_id, 'area': area})
            rows.append(('tb_shop', ['id', 'name', 'type_id', 'images', 'area', 'address', 'x', 'y',
                                    'avg_price', 'sold', 'comments', 'score', 'open_hours'],
                         [sid, title, type_id, cover, area, '虚拟体验街' + str(i + 1) + '号（非真实门店）',
                          x + i * .00001, y + i * .00001, price, 0, 0, 0, '10:00-22:00']))
            content = ('【模拟探店内容，非真实消费评价】\n这是一家用于演示的' + category + '门店，位于'
                       + area + '，预算参考为每人' + str(price) + '元。\n可以体验：查看门店、比较预算、查询优惠券以及询问小橙使用规则。'
                       + '\n图片为场景示意；地址、价格、营业时间和服务均为虚构。请勿据此实际到店或支付。')
            rows.append(('tb_blog', ['id', 'shop_id', 'user_id', 'title', 'images', 'content', 'liked', 'comments'],
                         [880001 + i, sid, authors[i % 3][0], '【演示】' + name + '｜' + str(price) + '元预算体验', cover, content, 0, 0]))
            pay = max(500, int(price * .8) * 100)
            rules = ('【演示券，不对应真实消费】适用门店：' + title + '。周一至周日10:00—22:00使用；'
                     + '到店前请预约，每单限用一张，不与其他优惠叠加。仅用于本地功能演示，不支持真实支付、退款或到店核销。')
            rows.append(('tb_voucher', ['id', 'shop_id', 'title', 'sub_title', 'rules', 'pay_value', 'actual_value', 'type', 'status'],
                         [880001 + i, sid, '【演示】' + str(price) + '元体验代金券', '全周可用 · 需预约 · 仅限模拟体验', rules, pay, price * 100, 0, 1]))
    for i in range(2):
        rows.append(('tb_voucher', ['id', 'shop_id', 'title', 'sub_title', 'rules', 'pay_value', 'actual_value', 'type', 'status'],
                     [880101 + i, shops[i]['id'], '【演示】免费领取10元抵扣券', '满60元可用 · 限本店 · 普通券',
                      '【演示券】仅限对应虚拟门店，消费满60元抵10元，每单一张，不可叠加；不对应真实交易。', 0, 1000, 0, 1]))
    return rows, shops


def create_covers():
    target = ROOT / 'frontend/assets/demo'
    target.mkdir(parents=True, exist_ok=True)
    palette = ['#fce9db', '#eee7f9', '#fbe5ed', '#e0eee8', '#f5eadb']
    for type_id, category, _, _ in CATEGORIES:
        svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600" viewBox="0 0 800 600">'
               '<rect width="800" height="600" fill="' + palette[(type_id - 1) % 5] + '"/>'
               '<circle cx="650" cy="115" r="65" fill="#fff" opacity=".5"/>'
               '<rect x="215" y="215" width="370" height="225" rx="14" fill="#fffaf3"/>'
               '<path d="M195 225L235 145H565L605 225Z" fill="#dc7851"/>'
               '<path d="M235 145H285L270 225H215M335 145H385V225H320M435 145H485L505 225H440M535 145H565L605 225H570" fill="#f5b895"/>'
               '<rect x="260" y="280" width="125" height="105" rx="8" fill="#d9e9df"/>'
               '<rect x="435" y="275" width="95" height="165" rx="8" fill="#7b9987"/>'
               '<circle cx="510" cy="360" r="5" fill="#fff"/>'
               '<text x="400" y="100" text-anchor="middle" font-family="Microsoft YaHei,sans-serif" font-size="32" fill="#624537">'
               + html.escape(category) + ' · 城市生活</text>'
               '<text x="400" y="515" text-anchor="middle" font-family="Microsoft YaHei,sans-serif" font-size="25" fill="#745f51">模拟门店 · 场景示意，非实拍</text></svg>')
        path = target / ('category-' + str(type_id) + '.svg')
        if not path.exists():
            path.write_text(svg, encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    rows, shops = build()
    counts = {table: sum(row[0] == table for row in rows) for table in ['tb_shop', 'tb_blog', 'tb_voucher', 'tb_user']}
    if not args.apply:
        print(json.dumps({'dryRun': True, 'planned': counts, 'writes': 0}, ensure_ascii=False))
        return
    # Refuse occupied IDs; repeat runs leave every existing row unchanged.
    for table, columns, values in rows:
        marker = values[columns.index('nick_name' if table == 'tb_user' else 'name' if table == 'tb_shop' else 'title')]
        field = 'nick_name' if table == 'tb_user' else 'name' if table == 'tb_shop' else 'title'
        conflict = mysql('SELECT COUNT(*) FROM ' + table + ' WHERE id=' + str(values[0]) + ' AND ' + field + '<>' + sql_value(marker))
        if conflict != '0':
            raise RuntimeError('Reserved demo ID is occupied: ' + table + '/' + str(values[0]))
    create_covers()
    before = {table: int(mysql('SELECT COUNT(*) FROM ' + table)) for table in counts}
    mysql('START TRANSACTION;\n' + '\n'.join(insert_missing(*row) for row in rows) + '\nCOMMIT;')
    after = {table: int(mysql('SELECT COUNT(*) FROM ' + table)) for table in counts}
    checks = []
    for shop in shops:
        with urllib.request.urlopen('http://127.0.0.1:8080/api/shop/' + str(shop['id']), timeout=20) as response:
            data = json.load(response)
        if not data.get('success') or data['data']['name'] != shop['name']:
            raise RuntimeError('Shop HTTP verification failed: ' + str(shop['id']))
        checks.append(shop['id'])
    report = {'checkedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'synthetic': True,
              'planned': counts, 'inserted': {t: after[t] - before[t] for t in counts},
              'existingRowsUpdated': 0, 'ordersCreated': 0, 'seckillChanged': False,
              'shopHttpChecks': checks, 'knowledgePublished': False,
              'note': 'Illustrated fictional stores. No real ratings, purchases, payment, or model calls.'}
    (ROOT / 'docs/demo-data-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
