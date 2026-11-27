"""Graph API 응답 한 페이지를 읽어 게시물을 파일에 이어 붙이고, 다음 페이지 커서를 출력한다.
사용: <응답> | python3 collect_page.py <own|bd> <out.json>
- 출력(stdout): 다음 페이지가 있으면 after 커서, 없으면 빈 줄
- 에러 응답이면 stderr에 code/subcode/message를 쓰고 종료 코드 2
- paging.next(토큰이 든 URL)는 저장하지 않는다. 커서 값만 쓴다.
"""
import json
import os
import sys


def main():
    mode, out = sys.argv[1], sys.argv[2]
    try:
        d = json.loads(sys.stdin.read())
    except Exception:
        print("JSON이 아닌 응답", file=sys.stderr)
        sys.exit(2)
    if "error" in d:
        e = d["error"]
        print(f"❌ code={e.get('code')} subcode={e.get('error_subcode')}: {e.get('message')}", file=sys.stderr)
        sys.exit(2)

    if mode == "bd":
        bd = d.get("business_discovery", {})
        media = bd.get("media", {})
        if "username" in bd:
            print(f"@{bd['username']} media_count={bd.get('media_count')}", file=sys.stderr)
    else:
        media = d

    items = json.load(open(out, encoding="utf-8")) if os.path.exists(out) else []
    items.extend(media.get("data", []))
    with open(out, "w", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False)

    paging = media.get("paging", {})
    has_next = "next" in paging or (mode == "bd" and paging.get("cursors", {}).get("after") and len(media.get("data", [])) > 0)
    print(paging.get("cursors", {}).get("after", "") if has_next else "")


if __name__ == "__main__":
    main()
