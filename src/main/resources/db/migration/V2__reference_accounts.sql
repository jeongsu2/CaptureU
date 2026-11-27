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
    failure_type      VARCHAR(30)  NULL,
    failure_message   VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_analysis_reference (analysis_id, username),
    CONSTRAINT fk_analysis_reference_analysis FOREIGN KEY (analysis_id) REFERENCES analysis (id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
