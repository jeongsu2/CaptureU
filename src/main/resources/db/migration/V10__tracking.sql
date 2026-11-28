-- 더 이어서 하기 (#5). 추적을 켠 계정만 새 게시물의 나이별 지표를 쌓는다.
CREATE TABLE tracking (
    ig_user_id  VARCHAR(32) NOT NULL,
    enabled     BIT(1)      NOT NULL,
    started_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (ig_user_id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 게시물의 나이별(게시 후 1·3·6·12·24·48·72시간, 이후 7일까지 하루 1번) 누적 지표.
-- API는 지금 누적값만 주므로 같은 나이끼리 비교하려면 그 시점 값을 직접 쌓아야 한다.
CREATE TABLE media_checkpoint (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    ig_user_id          VARCHAR(32)  NOT NULL,
    media_id            VARCHAR(32)  NOT NULL,
    checkpoint_hours    INT          NOT NULL,
    posted_at           DATETIME(6)  NOT NULL,
    captured_at         DATETIME(6)  NOT NULL,
    age_minutes         INT          NOT NULL,
    views               INT          NULL,
    reach               INT          NULL,
    saved               INT          NULL,
    shares              INT          NULL,
    total_interactions  INT          NULL,
    skip_rate           DOUBLE       NULL,
    caption_first_line  VARCHAR(100) NULL,
    permalink           VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_checkpoint (media_id, checkpoint_hours),
    KEY idx_media_checkpoint_owner (ig_user_id, checkpoint_hours)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
