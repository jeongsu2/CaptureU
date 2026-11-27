# Graph API 확인 스크립트 공통 부분. 다른 스크립트에서 source 해서 쓴다.
# - 토큰은 실행 중에 입력받고, 화면·파일·명령 인자(ps 목록)에 남기지 않는다.
# - 응답의 paging.next URL에는 access_token이 들어 있으므로 출력 전에 반드시 지운다.

: "${IG_ID:?IG_ID 환경변수에 내 인스타 계정 ID를 넣어 주세요 (앱 대시보드 '액세스 토큰 생성'에 표시됨)}"

if [ -z "${IG_TOKEN:-}" ]; then
  printf '토큰 붙여넣기 (입력해도 화면에 안 보임) 후 Enter: ' >&2
  IFS= read -rs IG_TOKEN; echo >&2
fi
[ -n "$IG_TOKEN" ] || { echo "토큰이 비어 있어요" >&2; exit 1; }

case "$IG_TOKEN" in
  IG*) HOST="graph.instagram.com" ;;   # Instagram 사용자 토큰
  *)   HOST="graph.facebook.com" ;;    # 시스템 사용자 토큰(SUAT) 등
esac

# call <path> [key=value ...] → 응답 본문
call() {
  local path="$1"; shift
  local args=() kv
  for kv in "$@"; do args+=(--data-urlencode "$kv"); done
  printf 'data-urlencode = "access_token=%s"\n' "$IG_TOKEN" |
    curl -sS -G -K - ${args[@]+"${args[@]}"} "https://$HOST/$path"
}

# call_headers <path> [key=value ...] → 응답 헤더만
call_headers() {
  local path="$1"; shift
  local args=() kv
  for kv in "$@"; do args+=(--data-urlencode "$kv"); done
  printf 'data-urlencode = "access_token=%s"\n' "$IG_TOKEN" |
    curl -sS -G -K - -D - -o /dev/null ${args[@]+"${args[@]}"} "https://$HOST/$path"
}

section() { printf '\n===== %s =====\n' "$1"; }
