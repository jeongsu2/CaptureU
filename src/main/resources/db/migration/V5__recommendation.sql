-- 추천 결과 (#13). 같은 분석에 주제를 바꿔 여러 번 추천받을 수 있다.
-- 채택·효과 검증(#14)에서 어떤 추천을 썼는지 이 ID로 연결한다.
CREATE TABLE recommendation (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    analysis_id      BIGINT        NOT NULL,
    topic            VARCHAR(100)  NOT NULL,
    -- LLM: Claude로 생성 / TEMPLATE: 규칙 기반 (키가 없거나 LLM 호출이 실패했을 때)
    generator        VARCHAR(20)   NOT NULL,
    model            VARCHAR(64)   NULL,
    prompt_version   VARCHAR(30)   NOT NULL,
    input_tokens     INT           NULL,
    output_tokens    INT           NULL,
    cost_usd         DECIMAL(10, 6) NULL,
    latency_ms       BIGINT        NOT NULL,
    -- LLM을 쓰지 못하고 템플릿으로 추천한 이유
    fallback_reason  VARCHAR(300)  NULL,
    result_json      TEXT          NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_recommendation_analysis (analysis_id),
    CONSTRAINT fk_recommendation_analysis FOREIGN KEY (analysis_id) REFERENCES analysis (id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
