#!/usr/bin/env bash
# 이슈 #1: 썸네일·캡션을 내 계정과 참고 계정에서 받을 수 있는지 확인한다.
# 사용: IG_ID=<내 인스타 계정 ID> bash scripts/probe_media_fields.sh <참고계정> [참고계정 ...] [없는아이디]
# 출력에는 토큰과 URL 원문이 들어가지 않는다.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib_graph.sh
source "$DIR/lib_graph.sh"

[ $# -gt 0 ] || { echo "참고 계정 아이디를 하나 이상 넣어 주세요" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
OWN_FIELDS="id,media_type,media_product_type,timestamp,caption,thumbnail_url,media_url,permalink,like_count,comments_count"
BD_FIELDS="id,media_type,media_product_type,timestamp,caption,thumbnail_url,media_url,permalink,like_count,comments_count,view_count"
MAX_PAGES=20

echo "호스트 $HOST"

section "1. 내 게시물 전체 (50개씩 페이지네이션)"
cursor=""; pages=0
while :; do
  pages=$((pages + 1))
  if [ -n "$cursor" ]; then
    resp="$(call "$IG_ID/media" fields="$OWN_FIELDS" limit=50 after="$cursor")"
  else
    resp="$(call "$IG_ID/media" fields="$OWN_FIELDS" limit=50)"
  fi
  cursor="$(printf '%s' "$resp" | python3 "$DIR/collect_page.py" own "$TMP/own.json")" || break
  [ -n "$cursor" ] && [ "$pages" -lt "$MAX_PAGES" ] || break
done
echo "받은 페이지: $pages"
[ -f "$TMP/own.json" ] && python3 "$DIR/summarize_media.py" "$TMP/own.json" --fetch-check

for ref in "$@"; do
  section "2. 참고 계정 @$ref (Business Discovery, 50개씩 2페이지까지)"
  out="$TMP/bd_$ref.json"
  resp="$(call "$IG_ID" "fields=business_discovery.username($ref){username,media_count,media.limit(50){$BD_FIELDS}}")"
  cursor="$(printf '%s' "$resp" | python3 "$DIR/collect_page.py" bd "$out")" || continue
  if [ -n "$cursor" ]; then
    resp="$(call "$IG_ID" "fields=business_discovery.username($ref){media.after($cursor).limit(50){$BD_FIELDS}}")"
    if printf '%s' "$resp" | python3 "$DIR/collect_page.py" bd "$out" >/dev/null; then
      echo "2페이지 받기: 성공"
    else
      echo "2페이지 받기: 실패"
    fi
  else
    echo "2페이지 없음 (게시물이 50개 이하이거나 커서 없음)"
  fi
  [ -f "$out" ] && python3 "$DIR/summarize_media.py" "$out" --fetch-check
done

section "3. 호출 한도 응답 헤더"
call_headers "$IG_ID" fields=id | grep -iE '^(x-app-usage|x-business-use-case-usage|x-ad-account-usage|x-fb-api-version):' || echo "(한도 관련 헤더 없음)"

printf '\n끝. 토큰과 URL 원문은 출력하지 않았어요.\n'
