-- 분석 요청 시점에 내 계정 데이터를 받아 저장하는 테이블 (#4)
-- 시각은 모두 UTC로 저장한다. 캡션에 이모지(4바이트)가 들어가므로 utf8mb4를 명시한다.

-- 분석 요청 한 건. 수집 소요 시간과 외부 API 호출 수를 남겨 비동기화(#18)의 '개선 전' 숫자로 쓴다.
CREATE TABLE analysis (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    ig_user_id            VARCHAR(32)  NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    requested_at          DATETIME(6)  NOT NULL,
    finished_at           DATETIME(6)  NULL,
    media_count           INT          NOT NULL DEFAULT 0,
    media_truncated       BIT(1)       NOT NULL DEFAULT b'0',
    insight_failed_count  INT          NOT NULL DEFAULT 0,
    api_call_count        INT          NOT NULL DEFAULT 0,
    duration_ms           BIGINT       NULL,
    failure_type          VARCHAR(30)  NULL,
    failure_message       VARCHAR(500) NULL,
    PRIMARY KEY (id),
    INDEX idx_analysis_user_status (ig_user_id, status),
    INDEX idx_analysis_user_requested (ig_user_id, requested_at)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 분석 시점의 계정 정보. media_count는 실제 게시물 수와 다를 수 있어 참고용으로만 남긴다.
CREATE TABLE account_snapshot (
    analysis_id          BIGINT      NOT NULL,
    ig_user_id           VARCHAR(32) NOT NULL,
    username             VARCHAR(64) NULL,
    followers_count      INT         NULL,
    reported_media_count INT         NULL,
    PRIMARY KEY (analysis_id),
    CONSTRAINT fk_account_snapshot_analysis FOREIGN KEY (analysis_id) REFERENCES analysis (id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 게시물. 분석할 때마다 최신 값으로 덮어쓴다(upsert).
CREATE TABLE ig_media (
    id                        VARCHAR(32)   NOT NULL,
    owner_ig_user_id          VARCHAR(32)   NOT NULL,
    media_type                VARCHAR(20)   NOT NULL,
    media_product_type        VARCHAR(20)   NOT NULL,
    posted_at                 DATETIME(6)   NOT NULL,
    caption                   TEXT          NULL,
    caption_first_line        VARCHAR(500)  NULL,
    thumbnail_url             VARCHAR(2048) NULL,
    thumbnail_url_expires_at  DATETIME(6)   NULL,
    permalink                 VARCHAR(512)  NULL,
    first_seen_at             DATETIME(6)   NOT NULL,
    last_seen_at              DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_media_owner_posted (owner_ig_user_id, posted_at)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 분석 요청별 게시물 지표. 같은 게시물을 다시 분석하면 행이 하나 더 쌓여 시간에 따른 비교 자료가 된다.
CREATE TABLE media_metric_snapshot (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    analysis_id         BIGINT       NOT NULL,
    media_id            VARCHAR(32)  NOT NULL,
    collected_at        DATETIME(6)  NOT NULL,
    age_hours           INT          NOT NULL,
    like_count          INT          NULL,
    comments_count      INT          NULL,
    views               INT          NULL,
    reach               INT          NULL,
    saved               INT          NULL,
    shares              INT          NULL,
    total_interactions  INT          NULL,
    avg_watch_time_ms   INT          NULL,
    skip_rate           DOUBLE       NULL,
    insight_error       VARCHAR(200) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshot_analysis_media (analysis_id, media_id),
    INDEX idx_snapshot_media_collected (media_id, collected_at),
    CONSTRAINT fk_snapshot_analysis FOREIGN KEY (analysis_id) REFERENCES analysis (id),
    CONSTRAINT fk_snapshot_media FOREIGN KEY (media_id) REFERENCES ig_media (id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 분석 시점의 팔로워·참여자 분포 (계정 단위)
CREATE TABLE audience_demographic (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    analysis_id      BIGINT      NOT NULL,
    metric           VARCHAR(40) NOT NULL,
    breakdown        VARCHAR(20) NOT NULL,
    dimension_value  VARCHAR(40) NOT NULL,
    value            INT         NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_demographic_analysis (analysis_id),
    CONSTRAINT fk_demographic_analysis FOREIGN KEY (analysis_id) REFERENCES analysis (id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
