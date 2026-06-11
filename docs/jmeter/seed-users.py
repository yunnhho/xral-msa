#!/usr/bin/env python3
"""부하 테스트용 계정 시드 (PRD M23: 측정 시나리오는 login부터 — 가입은 사전 준비).

auth-service(:8081)에 직접 signup 요청을 보내 loadtest 계정 N개를 만들고
JMeter CSV Data Set용 users.csv를 생성한다. 이미 존재하는 계정은 무시(멱등).

사용: python3 seed-users.py [N] [AUTH_URL]
"""
import json
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

N = int(sys.argv[1]) if len(sys.argv) > 1 else 1000
AUTH = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8081"
PASSWORD = "Test1234!"


def signup(i: int) -> str:
    email = f"loadtest{i:04d}@xrail.test"
    body = json.dumps({
        "email": email,
        "password": PASSWORD,
        "name": f"LoadTest {i:04d}",
        "phone": f"010-9{i % 1000:03d}-{i % 10000:04d}",
        "birthDate": "19900101",
    }).encode()
    req = urllib.request.Request(
        f"{AUTH}/api/auth/signup", data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        urllib.request.urlopen(req, timeout=30)
        return f"{email},created"
    except urllib.error.HTTPError as e:
        # 409/400 등 — 이미 존재하면 OK (멱등)
        detail = e.read().decode()[:120]
        if "DUPLICATE" in detail or e.code in (400, 409):
            return f"{email},exists"
        return f"{email},ERROR {e.code} {detail}"
    except Exception as e:  # noqa: BLE001
        return f"{email},ERROR {e}"


def main() -> None:
    with ThreadPoolExecutor(max_workers=8) as ex:
        results = list(ex.map(signup, range(1, N + 1)))

    errors = [r for r in results if ",ERROR" in r]
    out = Path(__file__).parent / "users.csv"
    with out.open("w") as f:
        for r in results:
            if ",ERROR" not in r:
                f.write(r.split(",")[0] + "," + PASSWORD + "\n")

    print(f"total={len(results)} ok={len(results) - len(errors)} error={len(errors)}")
    for e in errors[:5]:
        print(" ", e)


if __name__ == "__main__":
    main()
