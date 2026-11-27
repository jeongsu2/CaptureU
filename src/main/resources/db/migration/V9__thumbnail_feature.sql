-- 썸네일 특징 (#11). 같은 이미지는 다시 분석하지 않도록 이미지 내용의 SHA-256을 키로 쓴다.
-- 썸네일 URL은 며칠 뒤 만료되므로 이미지를 받아 해시를 낸 뒤, 이미 있는 해시면 그 결과를 쓴다.
CREATE TABLE thumbnail_feature (
    image_hash      CHAR(64)      NOT NULL,
    face_close_up   BIT(1)        NOT NULL,
    person_count    INT           NOT NULL,
    text_size       VARCHAR(10)   NOT NULL,
    text_content    VARCHAR(100)  NULL,
    tone            VARCHAR(20)   NOT NULL,
    composition     VARCHAR(20)   NOT NULL,
    model           VARCHAR(64)   NOT NULL,
    prompt_version  VARCHAR(30)   NOT NULL,
    input_tokens    INT           NOT NULL,
    output_tokens   INT           NOT NULL,
    cost_usd        DECIMAL(10, 6) NULL,
    created_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (image_hash)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 게시물 → 썸네일 이미지 해시. 게시물마다 한 번만 받는다.
CREATE TABLE media_thumbnail (
    media_id    VARCHAR(32) NOT NULL,
    image_hash  CHAR(64)    NOT NULL,
    tagged_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (media_id),
    KEY idx_media_thumbnail_hash (image_hash),
    CONSTRAINT fk_media_thumbnail_feature FOREIGN KEY (image_hash) REFERENCES thumbnail_feature (image_hash)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
