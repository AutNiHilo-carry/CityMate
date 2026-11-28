"""Read-only checks for the local voucher browse API. No login or claim calls."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/api")
    parser.add_argument("--report", default="docs/voucher-browse-verification.json")
    args = parser.parse_args()
    checks = []

    def get(path, **params):
        with urlopen(args.base_url + path + "?" + urlencode(params), timeout=15) as response:
            return json.load(response)

    def passed(name, condition):
        assert condition, name
        checks.append(name)

    first = get("/voucher/catalog", size=2)
    passed("public catalog returns bounded rows and a total", first["success"]
           and len(first["data"]) <= 2 and first["total"] >= len(first["data"]))
    assert first["data"], "Seed at least one published voucher before running this check."
    second = get("/voucher/catalog", size=2, page=2)
    passed("stable pagination has no repeated IDs", second["success"]
           and not ({row["id"] for row in first["data"]}
                    & {row["id"] for row in second["data"]}))
    row = first["data"][0]
    shop = get("/shop/" + str(row["shopId"]))["data"]
    types = get("/shop-type/list")["data"]
    category = shop["typeId"]
    filtered = get("/voucher/catalog", typeId=category, shopId=row["shopId"], size=20)
    passed("category and exact shop combine in the database", filtered["success"]
           and filtered["total"] > 0
           and all(v["shopId"] == row["shopId"] for v in filtered["data"]))
    for label, keyword in [("shop name", row["shopName"]), ("voucher title", row["title"])]:
        found = get("/voucher/catalog", keyword=keyword, shopId=row["shopId"], size=20)
        passed("keyword matches " + label, found["success"] and found["total"] > 0)
    preview = get("/voucher/preview", shopId=row["shopId"], voucherId=row["id"])
    passed("exact voucher preview returns current price and availability", preview["success"]
           and str(preview["data"]["voucherId"]) == str(row["id"])
           and preview["data"]["payCents"] == row["payValue"]
           and isinstance(preview["data"]["availableAtQuery"], bool))
    other_type = next((t["id"] for t in types if t["id"] != category), None)
    if other_type is not None:
        mismatch = get("/voucher/catalog", typeId=other_type, shopId=row["shopId"])
        passed("mismatched shop and category return no rows", mismatch["success"]
               and mismatch["total"] == 0 and not mismatch["data"])
    passed("invalid preview cannot supply any voucher", get("/voucher/preview",
           shopId=row["shopId"], voucherId=0)["success"] is False)
    facets = get("/voucher/shops", typeId=category)
    passed("shop options are bounded and expose truncation", facets["success"]
           and len(facets["data"]["shops"]) <= 200
           and isinstance(facets["data"]["hasMore"], bool))
    for criteria in [{"page": 0}, {"size": 21}, {"shopId": -1}, {"typeId": 0},
                     {"keyword": "x" * 101}]:
        passed("reject invalid " + next(iter(criteria)),
               get("/voucher/catalog", **criteria)["success"] is False)
    for keyword in ["%", "_", "!", "' OR 1=1 --"]:
        result = get("/voucher/catalog", keyword=keyword, size=20)
        passed("literal search: " + keyword, result["success"]
               and all(any(keyword.lower() in str(v.get(k, "")).lower()
                           for k in ["shopName", "title", "subTitle"])
                       for v in result["data"]))
    passed("availability is explicitly returned", isinstance(row.get("orderable"), bool))
    jar = Path("target/citymate-0.0.1-SNAPSHOT.jar")
    sources = ["frontend/index.html", "frontend/scripts/app.js", "frontend/assets/business-ui.css",
               "src/main/java/com/hmdp/service/impl/VoucherBrowseService.java",
               "src/main/resources/mapper/VoucherBrowseMapper.xml"]
    report = {"checkedAtUtc": datetime.now(timezone.utc).isoformat(),
              "readOnly": True, "checks": checks, "failures": 0,
              "observedTotal": first["total"],
              "browserVisualCheck": False,
              "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
              "sourceHashes": {f: hashlib.sha256(Path(f).read_bytes()).hexdigest() for f in sources}}
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                                 encoding="utf-8")
    print(json.dumps({"checksPassed": len(checks), "observedTotal": first["total"]}))


if __name__ == "__main__":
    main()
