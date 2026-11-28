# CaptureU

> 내 계정에서 잘된 게시물과 내가 고른 참고 계정(1~3개)을 분석해서, 다음 릴스의 **썸네일 구성안**과 **캡션 첫 줄 후킹 문구**를 추천하는 인스타그램 크리에이터용 서비스

## 무엇을 하나

1. **내 계정 분석:** 지금까지 올린 게시물의 썸네일, 캡션 첫 줄, 조회수, 저장, 공유, **릴스 스킵률**을 모은다. 계정 중앙값보다 잘된 게시물과 아닌 게시물의 차이를 찾는다.
2. **참고 계정 1~3개 분석 (3개 권장):** 사용자가 직접 고른 계정의 공개 지표(조회수, 댓글 수, 좋아요 수)와 썸네일, 캡션을 같은 기준으로 분석한다. 3개면 "3개 중 2개 이상에서 공통으로 잘된 패턴"을 근거로 쓸 수 있다.
3. **추천:** 두 분석을 합쳐 다음 게시물의 썸네일 구성안(넣을 글자, 구도)과 첫 줄 후킹 문구 후보를 근거와 함께 보여 준다.
4. **효과 검증:** 추천을 쓴 게시물의 스킵률과 조회수를 내 계정 중앙값과 비교한다.
5. **더 이어서 하기 (선택):** 결과 다음 화면에서 원하는 사용자만 게시물 추적을 켜면, 올리자마자 반응이 좋은 게시물(초반 급상승)을 감지한다.

## 원칙

- **공식 Instagram Graph API만 쓴다.** 스크래핑하지 않는다.
- **요청한 그 시점을 분석한다.** 매일 데이터를 쌓지 않는다. 추적은 '더 이어서 하기'를 누른 사용자만 한다.
- **같은 기준으로 비교한다.** 평균이 아니라 중앙값 배수를 쓴다. 게시 7일 미만 게시물은 아직 반응이 쌓이는 중으로 본다.
- **먼저 단순하게 만들고 잰 뒤에 개선한다.** 성능 개선은 개선 전 숫자가 있을 때만 한다.

## 데이터로 알 수 있는 것

| | 내 계정 | 참고 계정 (Business Discovery) |
|---|---|---|
| 썸네일 이미지 | ✅ (URL은 며칠 뒤 만료) | ✅ (URL은 며칠 뒤 만료) |
| 캡션 | ✅ | ✅ |
| 조회수 | ✅ | ✅ 릴스만 |
| 좋아요, 댓글 수 | ✅ | ✅ 좋아요를 숨긴 계정은 좋아요 없음 |
| 저장, 공유, 도달 | ✅ | ❌ |
| 릴스 스킵률, 평균 시청 시간 | ✅ | ❌ |
| 댓글 본문 | ✅ | ❌ |
| 팔로워 연령·성별 | ✅ 계정 단위 | ❌ |
| 개인 계정 | - | ❌ 프로페셔널 계정만 조회 가능 |

자세한 확인 결과: [docs/spike-meta-api.md](docs/spike-meta-api.md)

## 기술 스택

- 지금: Java 21 (가상 스레드), Spring Boot 4.1, Spring Data JPA, MySQL 8.4, Redis 8.4, Flyway, Docker Compose, Testcontainers, GitHub Actions
- 계획: Kafka, Prometheus, Grafana, k6, LLM API

## 코드 구조

```
com.captureu
├── domain/<기능>          analysis, instagram, llm, profile, recommendation, reference, thumbnail
│   ├── controller         REST API
│   ├── dto/request·response  요청·응답 본문
│   ├── entity             JPA 엔티티
│   ├── repository         Spring Data JPA
│   ├── service            비즈니스 로직
│   ├── client             외부 API 호출 (Instagram Graph API, Claude)
│   ├── exception · config · util
└── global                 config, error(공통 예외 처리), util
```

## 측정한 개선

실제 계정(게시물 193개)과 참고 계정 3개로 분석 요청 한 건을 쟀다. 개선은 항상 먼저 재고 나서 했다.

| 개선 | 전 | 후 | 이슈 |
|---|---|---|---|
| 게시물 인사이트 병렬 수집 (동시 호출 8개로 제한, 치명적 에러 시 즉시 중단) | 139.9초 | **22.9초** (6.1배) | #30 |
| 참고 계정 공유 캐시 (Redis, TTL 6시간) | 참고 계정당 외부 호출 2회 | **0회** (분석당 207 → 201회) | #9 |

- 동시 호출 수별: 1개 139.9초 / 4개 36.7초 / 8개 22.9초 / 16개 17.4초. 8 → 16은 호출을 두 배로 몰아 쓰는데 5초만 줄어서 8을 기본값으로 했다.
- 캐시는 응답 시간에는 효과가 없었다. 병렬화 이후 참고 계정은 내 계정과 동시에 모아서 그 시간이 이미 숨어 있었다. 대신 호출 한도를 아낀다.

## 로컬 실행

필요한 것: JDK 21, Docker Desktop (실행 중이어야 함)

1. IntelliJ → **Open** → 이 폴더 선택 → Gradle 프로젝트로 불러오기 (Project SDK 21)
2. `CaptureuApplication`을 실행 (Run)
   - `compose.yaml`의 MySQL, Redis가 자동으로 뜨고 연결된다
3. http://localhost:8080/actuator/health 에서 `db`, `redis`가 `UP`인지 확인

터미널에서:

```bash
./gradlew bootRun   # 실행
./gradlew test      # 테스트 (Testcontainers로 MySQL, Redis를 따로 띄움)
```

### Claude 키 없이 LLM 경로 확인하기 (목 모드)

```bash
./gradlew bootRun --args='--spring.profiles.active=local,mock-llm'
```

앱 안의 목 서버(`/mock-anthropic/v1/messages`)가 Messages API와 같은 모양으로 답해서, 결제 없이 추천 문구 생성과 썸네일 분석이 실제 SDK 호출 흐름을 끝까지 탄다. 답은 `[목]`이 붙은 고정 문장과 이미지 해시로 정한 특징이라 품질·비용 확인용은 아니다. 목 결과는 프롬프트 버전에 `-mock`이 붙어(`hook-v3-mock`, `thumb-v1-mock`) 나중에 실제 키로 분석할 때 캐시로 쓰이지 않고 다시 분석된다.

### 인스타 로그인 설정 (#16)

로그인하지 않으면 로컬 개발용으로 `.env`의 `IG_USER_ID`·`IG_ACCESS_TOKEN` 계정을 쓴다. 인스타 로그인을 쓰려면 `.env`에 아래 값을 넣는다 (`.env.example` 참고).

1. Meta 앱 대시보드: 비즈니스용 Facebook 로그인 → 구성을 만들고 구성 ID를 `META_LOGIN_CONFIG_ID`에. 토큰 유형은 시스템 사용자 액세스 토큰, 자산은 Instagram 계정, 권한은 `instagram_basic`·`instagram_manage_insights` (페이스북 페이지 없이 로그인하는 Unified Login 방식. 인스타 계정은 `me/assigned_instagram_accounts`로 찾고, 없으면 페이지에 연결된 계정을 찾는다)
2. 같은 화면의 설정 → 유효한 OAuth 리디렉션 URI에 `META_REDIRECT_URI`(예: `http://localhost:8080/api/auth/callback`)를 등록. Meta가 HTTPS 주소만 받으면 터널(예: `cloudflared tunnel --url http://localhost:8080`)의 HTTPS 주소 + `/api/auth/callback`을 등록하고 같은 값을 `META_REDIRECT_URI`에 넣는다
3. 앱 ID·시크릿을 `META_APP_ID`·`META_APP_SECRET`에, `openssl rand -base64 32`로 만든 값을 `TOKEN_ENCRYPTION_KEY`에

화면 위쪽의 "인스타로 로그인"을 누르면 Meta 로그인 → 돌아와서 토큰을 AES-GCM으로 암호화해 저장하고 세션에 계정을 넣는다. 분석·추천·썸네일·효과는 로그인한 계정의 것만 볼 수 있고(남의 분석 ID는 404), 추적 스케줄러는 추적을 켠 계정마다 그 계정의 토큰으로 돈다. `.env` 계정 대체는 로컬 개발 프로필에서만 켜지고 기본은 꺼져 있다. HTTPS로 배포하면 `SESSION_COOKIE_SECURE=true`.

### 화면으로 써 보기

`.env`를 채우고 앱을 실행한 뒤 http://localhost:8080 에서 컨셉 선택 → 참고 계정 등록 → 분석 → 비교 리포트 → 주제 입력·추천 → 문구 복사·채택까지 한 화면에서 할 수 있다.

### 내 계정 분석해 보기 (API)

1. `.env.example`을 복사해 `.env`를 만들고 `IG_ACCESS_TOKEN`, `IG_USER_ID`를 채운다 (`.env`는 커밋되지 않는다)
2. 앱을 실행하고, 비교할 참고 계정 1~3개를 등록한다 (3개 권장, 프로페셔널 계정만 가능)

```bash
curl -X PUT http://localhost:8080/api/reference-accounts -H 'Content-Type: application/json' -d '{"usernames":["참고계정1","참고계정2","참고계정3"]}'
```

3. 분석을 요청한다

```bash
curl -X POST http://localhost:8080/api/analyses
```

응답에 게시물 수, 인사이트를 못 받은 게시물 수, 참고 계정별 수집 결과, 외부 API 호출 수, 걸린 시간(ms)이 나온다. 결과는 `GET /api/analyses/{id}`로 다시 볼 수 있다.

4. 비교 리포트를 본다

```bash
curl http://localhost:8080/api/analyses/{id}/report
```

계정마다 게시 7일 이상 릴스의 조회수 중앙값과, 중앙값의 2배 이상인 아웃라이어 카드(썸네일, 캡션 첫 줄, 배수)가 나온다. 내 계정은 스킵률·저장률을 아웃라이어와 나머지로 나눠 비교한다. 배수만 보려면 `/api/analyses/{id}/outliers`. 계정마다 캡션 첫 줄 유형(질문형, 목록형 등)별 아웃라이어 비율도 나온다.

5. 다음 게시물 주제로 첫 줄 후보와 썸네일 구성안을 추천받는다

```bash
curl -X POST http://localhost:8080/api/analyses/{id}/recommendations -H 'Content-Type: application/json' -d '{"topic":"겨울 출근룩"}'
```

참고 계정 2개 이상에서 잘된 첫 줄 유형을 먼저 추천하고, 한 계정에서만 잘된 유형은 그 계정 스타일로 따로 표시한다. 후보마다 근거(예: 참고 계정 3개 중 2개, 9개 중 4개가 아웃라이어)가 붙는다. `.env`에 `ANTHROPIC_API_KEY`가 있으면 Claude로 문구를 만들고, 없거나 호출이 실패하면 규칙 기반 틀로 만든다 (`generator`, `fallbackReason`으로 구분). 토큰, 비용, 걸린 시간도 함께 저장한다. 컨셉(`PUT /api/profile`, 예: `{"main":"BEAUTY","sub":"FASHION"}`)을 골라 두면 Claude 프롬프트에 넣는다.

6. 쓸 문구를 채택한다 (`hookIndex`는 0부터)

```bash
curl -X POST http://localhost:8080/api/recommendations/{추천 id}/adoptions -H 'Content-Type: application/json' -d '{"hookIndex":0}'
```

7. (선택) 썸네일 특징을 분석한다 — `ANTHROPIC_API_KEY` 필요

```bash
curl -X POST http://localhost:8080/api/analyses/{id}/thumbnails   # 분석 (계정마다 잘된 릴스·나머지 최대 15개씩)
curl http://localhost:8080/api/analyses/{id}/thumbnails            # 특징별 비율 비교
```

썸네일을 받아 얼굴 클로즈업, 화면 속 글자 크기, 색감, 구도를 Claude 비전으로 뽑는다. 같은 이미지(SHA-256)는 다시 분석하지 않고, 호출마다 토큰과 비용을 저장한다. 잘된 썸네일에서 뚜렷하게 많은 특징은 추천 썸네일 구성안의 근거로 쓴다. 인스타그램 CDN의 https 주소만 받는다.

8. 올린 뒤 다시 분석하면 추천 효과를 본다

```bash
curl http://localhost:8080/api/analyses/{id}/effects
```

새 게시물 캡션 첫 줄을 채택 문구와 비교해(공백·기호를 뺀 글자 2개 묶음이 겹치는 정도) 충분히 비슷하면 자동으로 연결하고, 애매하면 "이 게시물에 썼나요?"로 묻는다 (`POST /api/adoptions/{id}/match`). 연결한 게시물 중 게시 7일이 지난 것의 스킵률·저장률·조회수를 내 중앙값과 비교한다. 주제·시기도 다르므로 차이를 추천 효과로 단정하지 않는다는 안내를 함께 보여 준다.


9. (선택) 더 이어서 하기 — 올린 릴스의 초반 반응 추적

```bash
curl -X PUT http://localhost:8080/api/tracking -H 'Content-Type: application/json' -d '{"enabled":true}'
curl http://localhost:8080/api/tracking
```

추적을 켠 계정만 10분마다 최근 릴스를 확인해 게시 후 1·3·6·12·24·48·72시간, 이후 7일까지 하루 1번 조회수·스킵률을 쌓는다 (API는 지금 누적값만 주기 때문). 각 릴스의 가장 최근 나이 값을 같은 나이의 지난 릴스 중앙값과 비교해 2배 이상이면 초반 급상승으로 표시한다. 지난 릴스가 3개 이상 쌓여야 비교한다.

## 진행

작업은 [Issues](../../issues)와 [Milestones](../../milestones)로 관리한다.

| 마일스톤 | 내용 |
|---|---|
| M1 수집 | Graph API 클라이언트, 분석 요청 시 내 계정 게시물·인사이트 수집 |
| M2 비교 | 참고 계정 수집, 아웃라이어 계산, 공유 캐시 |
| M3 추천 | 썸네일 특징 추출, 첫 줄 분류, 추천, 효과 검증, 최소 웹 화면 |
| M4 운영 | 로그인 흐름, 모니터링, 비동기 처리, 부하 테스트, 배포 |
| M5 이어서 하기 | '더 이어서 하기'를 누른 사용자 대상 게시물 추적, 초반 급상승 감지 |
