"""Read-only checks against the local catalog. Does not create an authenticated session."""
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen

base = "http://127.0.0.1:8080/api"
checks = []


def get(path, **params):
    with urlopen(base + path + "?" + urlencode(params), timeout=12) as response:
        return json.load(response)


def passed(name, condition):
    assert condition, name
    checks.append(name)


first = get("/shop/catalog", page=1, size=3)
passed("bounded cross-category catalog", first["success"] and 0 < len(first["data"]) <= 3)
row = next(s for s in first["data"] if s.get("avgPrice") is not None and s.get("score") is not None)
combined = get("/shop/catalog", keyword=row["name"], typeId=row["typeId"],
               maxPrice=row["avgPrice"], minScore=row["score"], area=row.get("area") or "", size=20)
passed("combined budget rating category area filters", combined["success"]
       and any(s["id"] == row["id"] for s in combined["data"]))
literal = get("/shop/catalog", keyword="%", size=20)
passed("percent keyword is literal", literal["success"]
       and all("%" in s["name"] for s in literal["data"]))
budget = get("/shop/catalog", maxPrice=50, size=20)
passed("budget uses yuan rather than cents", budget["success"]
       and all(s["avgPrice"] is not None and s["avgPrice"] <= 50 for s in budget["data"]))
score = get("/shop/catalog", minScore=45, size=20)
passed("rating uses database tenths", score["success"]
       and all(s["score"] is not None and s["score"] >= 45 for s in score["data"]))
offers = get("/shop/catalog", hasVoucher="true", size=20)
passed("voucher filter returns a successful catalog", offers["success"])
for shop in offers["data"]:
    vouchers = get("/voucher/list/" + str(shop["id"]))
    passed("matching shop has an active voucher: " + str(shop["id"]), vouchers["success"]
           and any(v.get("orderable") is True for v in vouchers["data"]))
for params in [{"maxPrice": -1}, {"minScore": 51}, {"area": "x" * 51}, {"size": 21}]:
    passed("reject invalid " + next(iter(params)), get("/shop/catalog", **params)["success"] is False)
second = get("/shop/catalog", page=2, size=3)
passed("stable pagination without duplicate IDs", second["success"]
       and not ({s["id"] for s in first["data"]} & {s["id"] for s in second["data"]}))
try:
    get("/voucher-order/mine/vouchers", page=1)
except HTTPError as error:
    passed("held vouchers require authentication", error.code == 401)
else:
    raise AssertionError("Private order query accepted an anonymous request")
report = {"checks": checks, "failures": 0, "readOnly": True,
          "catalogTotalObserved": first["total"], "matchingVoucherShopsObserved": len(offers["data"])}
Path("target/catalog-orders-checks").mkdir(parents=True, exist_ok=True)
Path("target/catalog-orders-checks/live-api.json").write_text(
    json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"checksPassed": len(checks), "failures": 0}))
