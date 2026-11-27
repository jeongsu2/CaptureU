-- 크리에이터 컨셉 (#15). 메인 1개와 2순위 1개. 추천 프롬프트에 넣는다.
-- 지금은 .env의 계정 하나 기준이라 인스타 계정 ID를 키로 쓴다 (사용자 로그인은 #16).
CREATE TABLE creator_profile (
    ig_user_id    VARCHAR(32) NOT NULL,
    main_concept  VARCHAR(20) NOT NULL,
    sub_concept   VARCHAR(20) NULL,
    updated_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (ig_user_id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 추천 문구 채택 기록 (#15, #14). "이거 쓸게요"를 누른 첫 줄 후보.
-- 효과 검증(#14)에서 새 게시물 캡션과 비교해 추천을 쓴 게시물인지 판별한다.
CREATE TABLE recommendation_adoption (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    recommendation_id  BIGINT       NOT NULL,
    hook_index         INT          NOT NULL,
    hook_text          VARCHAR(500) NOT NULL,
    hook_type          VARCHAR(20)  NOT NULL,
    prompt_version     VARCHAR(30)  NOT NULL,
    adopted_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_adoption_recommendation (recommendation_id),
    CONSTRAINT fk_adoption_recommendation FOREIGN KEY (recommendation_id) REFERENCES recommendation (id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
