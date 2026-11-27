#!/usr/bin/env bash
# Meta Unified Login 스파이크: 대시보드에서 받은 토큰 하나로 무엇을 받을 수 있는지 확인한다.
# 사용: IG_ID=<내 인스타 계정 ID> bash ig_probe.sh [참고계정아이디 ...]
#       (참고 계정을 안 주면 instagram 공식 계정으로 시험. IG_ID는 앱 대시보드 "액세스 토큰 생성"에 표시됨)
# 토큰은 실행 중에 입력받는다. 화면, 파일, 명령 인자 어디에도 남기지 않는다.
set -u

: "${IG_ID:?IG_ID 환경변수에 내 인스타 계정 ID를 넣어 주세요}"
if [ $# -gt 0 ]; then REFS=("$@"); else REFS=(instagram); fi

if [ -z "${IG_TOKEN:-}" ]; then
  printf '토큰 붙여넣기 (입력해도 화면에 안 보임) 후 Enter: ' >&2
  IFS= read -rs IG_TOKEN; echo >&2
fi
[ -n "$IG_TOKEN" ] || { echo "토큰이 비어 있어요" >&2; exit 1; }

case "$IG_TOKEN" in
  IG*) HOST="graph.instagram.com" ;;   # Instagram 사용자 토큰
  *)   HOST="graph.facebook.com" ;;    # 시스템 사용자 토큰(SUAT) 등
esac

# 토큰은 curl 설정을 표준입력으로 넘겨서 명령 인자(ps 목록)에 드러나지 않게 한다.
call() {
  local path="$1"; shift
  local args=()
  local kv
  for kv in "$@"; do args+=(--data-urlencode "$kv"); done
  printf 'data-urlencode = "access_token=%s"\n' "$IG_TOKEN" |
    curl -sS -G -K - ${args[@]+"${args[@]}"} "https://$HOST/$path"
}

# 응답을 보기 좋게 (길면 자름). JSON이 아니면 그대로.
# Graph API는 paging.next/previous URL에 access_token을 그대로 넣어 돌려준다.
# 그래서 paging을 통째로 지우고, 혹시 남은 access_token=... 도 가린다.
show() {
  python3 -c '
import json, re, sys
def strip(o):
    if isinstance(o, dict):
        return {k: strip(v) for k, v in o.items() if k != "paging"}
    if isinstance(o, list):
        return [strip(v) for v in o]
    return o
def mask(s):
    return re.sub(r"access_token=[^&\"\s]+", "access_token=***", s)
raw = sys.stdin.read()
try:
    d = json.loads(raw)
except Exception:
    print(mask(raw[:800])); sys.exit()
if "error" in d:
    e = d["error"]
    print(mask("❌ code=%s subcode=%s: %s" % (e.get("code"), e.get("error_subcode"), e.get("message"))))
else:
    print(mask(json.dumps(strip(d), ensure_ascii=False, indent=1)[:2500]))
'
}

# 지표 하나 → 한 줄 (값 또는 에러)
one() {
  python3 -c '
import json, sys
try:
    d = json.loads(sys.stdin.read())
except Exception:
    print("❌ JSON 아님"); sys.exit()
if "error" in d:
    print("❌", d["error"].get("message", "")[:150]); sys.exit()
rows = d.get("data", [])
if not rows:
    print("(빈 응답)"); sys.exit()
r = rows[0]
if "values" in r:
    print("✅", r["values"][0].get("value"))
elif "total_value" in r:
    print("✅", json.dumps(r["total_value"], ensure_ascii=False)[:300])
else:
    print("✅", json.dumps(r, ensure_ascii=False)[:300])
'
}

section() { printf '\n===== %s =====\n' "$1"; }

echo "토큰 앞 2글자: ${IG_TOKEN:0:2} → 호스트 $HOST"

section "1. 내 계정 기본 정보"
call "$IG_ID" fields=id,username,followers_count,media_count | show

section "2. 최근 게시물 10개"
MEDIA_JSON="$(call "$IG_ID/media" fields=id,media_product_type,timestamp,like_count,comments_count,caption,thumbnail_url,media_url,permalink limit=10)"
printf '%s' "$MEDIA_JSON" | show
REEL_ID="$(printf '%s' "$MEDIA_JSON" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin).get("data", [])
except Exception:
    d = []
r = [m["id"] for m in d if m.get("media_product_type") == "REELS"]
print(r[0] if r else (d[0]["id"] if d else ""))
')"

if [ -n "$REEL_ID" ]; then
  section "3. 가장 최근 릴스 인사이트 (지표별로 따로 호출)"
  for m in views reach saved shares total_interactions ig_reels_avg_watch_time reels_skip_rate; do
    printf '%-26s ' "$m"
    call "$REEL_ID/insights" metric="$m" | one
  done

  section "4. 그 릴스의 댓글 (최대 3개)"
  call "$REEL_ID/comments" fields=text,timestamp limit=3 | show
else
  section "3~4. 게시물이 없거나 목록을 못 받아서 건너뜀"
fi

section "5. 팔로워·참여자 연령대 (계정 단위)"
for b in age gender; do
  printf '%-34s ' "follower_demographics/$b"
  call "$IG_ID/insights" metric=follower_demographics period=lifetime metric_type=total_value timeframe=this_month breakdown="$b" | one
done
printf '%-34s ' "engaged_audience_demographics/age"
call "$IG_ID/insights" metric=engaged_audience_demographics period=lifetime metric_type=total_value timeframe=this_month breakdown=age | one

for ref in "${REFS[@]}"; do
  section "6. 참고 계정 Business Discovery: @$ref"
  call "$IG_ID" "fields=business_discovery.username($ref){username,followers_count,media_count,media.limit(5){timestamp,media_product_type,like_count,comments_count,view_count,caption,thumbnail_url,media_url,permalink}}" | show
done

printf '\n끝. paging(토큰이 든 다음 페이지 URL)은 지우고 출력했어요. 그래도 붙여넣기 전에 EAA, IGAA로 시작하는 긴 문자열이 없는지 한 번 확인하세요.\n'
