"""게시물 목록(JSON 배열 파일)에서 썸네일·캡션 관련 필드가 얼마나 오는지 요약한다.
사용: python3 summarize_media.py <media.json> [--fetch-check]
- 출력에는 URL 원문을 넣지 않는다 (호스트와 만료 시각만).
- --fetch-check: 썸네일/이미지 URL을 실제로 1바이트만 받아 보고 상태 코드와 형식을 확인한다.
"""
import json
import sys
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone

FIELDS = ["caption", "thumbnail_url", "media_url", "permalink", "view_count", "like_count", "comments_count"]


def first_line(caption):
    for line in (caption or "").splitlines():
        if line.strip():
            return line.strip()
    return ""


def url_info(url):
    """호스트와 fbcdn 만료 파라미터(oe, 16진수 유닉스 시각)를 돌려준다."""
    p = urllib.parse.urlparse(url)
    q = urllib.parse.parse_qs(p.query)
    expiry = None
    if "oe" in q:
        try:
            expiry = datetime.fromtimestamp(int(q["oe"][0], 16), tz=timezone.utc)
        except ValueError:
            pass
    return p.netloc, expiry


def fetch_check(url):
    req = urllib.request.Request(url, headers={"Range": "bytes=0-0", "User-Agent": "captureu-probe"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            total = (r.headers.get("Content-Range") or "").split("/")[-1]
            return f"HTTP {r.status}, {r.headers.get('Content-Type')}, 전체 {total or r.headers.get('Content-Length')} bytes"
    except Exception as e:  # 상태 코드나 네트워크 오류를 그대로 보여 준다
        return f"실패: {e}"


def main():
    items = json.load(open(sys.argv[1], encoding="utf-8"))
    do_fetch = "--fetch-check" in sys.argv
    n = len(items)
    if n == 0:
        print("게시물 0개")
        return

    by_product = Counter(m.get("media_product_type", "?") for m in items)
    by_type = Counter(m.get("media_type", "?") for m in items)
    print(f"총 {n}개 | product: {dict(by_product)} | media_type: {dict(by_type)}")

    print("필드별로 값이 있는 개수 (전체 / 릴스 / 그 외):")
    reels = [m for m in items if m.get("media_product_type") == "REELS"]
    others = [m for m in items if m.get("media_product_type") != "REELS"]
    for f in FIELDS:
        a = sum(1 for m in items if m.get(f) not in (None, ""))
        r = sum(1 for m in reels if m.get(f) not in (None, ""))
        o = sum(1 for m in others if m.get(f) not in (None, ""))
        print(f"  {f:<15} {a:>3}/{n:<3} | {r:>3}/{len(reels):<3} | {o:>3}/{len(others)}")

    lines = [first_line(m.get("caption")) for m in items if m.get("caption")]
    if lines:
        print(f"캡션 첫 줄 예시 ({min(3, len(lines))}개, 40자까지):")
        for line in lines[:3]:
            print("  -", line[:40])
        emoji_only = sum(1 for line in lines if line and not any(ch.isalnum() for ch in line))
        print(f"  글자 없이 이모지·기호만 있는 첫 줄: {emoji_only}개")

    for key in ("thumbnail_url", "media_url"):
        urls = [m[key] for m in items if m.get(key)]
        if not urls:
            continue
        host, expiry = url_info(urls[0])
        exp = expiry.strftime("%Y-%m-%d %H:%M UTC") if expiry else "만료 파라미터 없음"
        print(f"{key} 예시: 호스트 {host}, 만료 {exp}")
        if do_fetch:
            print(f"  실제로 받아 보기: {fetch_check(urls[0])}")


if __name__ == "__main__":
    main()
