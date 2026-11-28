-- 인스타 로그인 사용자 (#16). 토큰은 AES-GCM으로 암호화해 저장한다 (평문을 DB에 두지 않는다).
CREATE TABLE app_user (
    ig_user_id        VARCHAR(32)  NOT NULL,
    username          VARCHAR(64)  NULL,
    access_token_enc  TEXT         NOT NULL,
    token_expires_at  DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (ig_user_id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
