-- 참고 계정 (#7). 사용자(내 계정)마다 최대 2개.
CREATE TABLE reference_account (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    owner_ig_user_id  VARCHAR(32) NOT NULL,
    username          VARCHAR(30) NOT NULL,
    ig_id             VARCHAR(32) NULL,
    followers_count   INT         NULL,
    registered_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reference_owner_username (owner_ig_user_id, username)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 분석 요청별 참고 계정 수집 결과. 참고 계정 하나가 실패해도 분석 전체는 실패시키지 않는다.
-- 게시물은 ig_media(owner_ig_user_id = 참고 계정 ID)에, 지표는 media_metric_snapshot에 내 계정과 같은 형태로 저장한다.
CREATE TABLE analysis_reference (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    analysis_id       BIGINT       NOT NULL,
    username          VARCHAR(30)  NOT NULL,
    ig_id             VARCHAR(32)  NULL,
    status            VARCHAR(20)  NOT NULL,
    followers_count   INT          NULL,
    media_count       INT          NOT NULL DEFAULT 0,
    api_call_count    INT          NOT NULL DEFAULT 0,
    duration_ms       BIGINT       NULL,
    failure_type      VARCHAR(30)  NULL,
    failure_message   VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_analysis_reference (analysis_id, username),
    CONSTRAINT fk_analysis_reference_analysis FOREIGN KEY (analysis_id) REFERENCES analysis (id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 지표 스냅샷에 "어느 계정의 게시물로 수집했는지"를 남긴다.
-- 협업(collab) 게시물은 두 계정 모두의 게시물로 오므로, ig_media의 소유자 하나만으로는 계정을 나눌 수 없다.
ALTER TABLE media_metric_snapshot ADD COLUMN account_ig_id VARCHAR(32) NULL AFTER media_id;

UPDATE media_metric_snapshot s JOIN ig_media m ON m.id = s.media_id
SET s.account_ig_id = m.owner_ig_user_id;

ALTER TABLE media_metric_snapshot
    MODIFY account_ig_id VARCHAR(32) NOT NULL,
    ADD UNIQUE KEY uk_snapshot_analysis_account_media (analysis_id, account_ig_id, media_id);

ALTER TABLE media_metric_snapshot DROP INDEX uk_snapshot_analysis_media;
