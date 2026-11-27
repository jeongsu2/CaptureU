-- 채택별로 "이 게시물 아니에요"를 누른 게시물을 모두 기억한다 (#14 리뷰).
-- V7에서는 뗀 게시물을 연결 칸 하나에 같이 적어, 다음 연결이 생기면 뗀 기록이 사라졌다.
CREATE TABLE adoption_rejection (
    adoption_id  BIGINT      NOT NULL,
    media_id     VARCHAR(32) NOT NULL,
    rejected_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (adoption_id, media_id),
    CONSTRAINT fk_rejection_adoption FOREIGN KEY (adoption_id) REFERENCES recommendation_adoption (id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO adoption_rejection (adoption_id, media_id, rejected_at)
SELECT id, matched_media_id, COALESCE(matched_at, adopted_at)
FROM recommendation_adoption
WHERE match_status = 'REJECTED' AND matched_media_id IS NOT NULL;

-- 연결 칸에는 사용자가 확인한 게시물(CONFIRMED)만 남긴다. 자동 연결은 조회할 때마다 계산한다
UPDATE recommendation_adoption
SET match_status = NULL, matched_media_id = NULL, match_score = NULL, matched_at = NULL
WHERE match_status <> 'CONFIRMED';

-- 한 게시물은 한 채택에만 연결된다 (동시에 확인해도 DB에서 막는다). 동시 수정은 version으로 막는다
ALTER TABLE recommendation_adoption
    ADD UNIQUE KEY uk_adoption_matched_media (matched_media_id),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
