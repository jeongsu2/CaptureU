-- 채택한 첫 줄을 실제로 쓴 게시물 (#14).
-- AUTO: 캡션 첫 줄이 채택 문구와 충분히 비슷해 자동으로 연결 / CONFIRMED: 사용자가 "이 게시물에 썼어요"로 연결
-- REJECTED: 사용자가 "아니에요"로 뗌. 같은 게시물에 다시 자동 연결하지 않는다
ALTER TABLE recommendation_adoption
    ADD COLUMN match_status    VARCHAR(20) NULL AFTER adopted_at,
    ADD COLUMN matched_media_id VARCHAR(32) NULL AFTER match_status,
    ADD COLUMN match_score     DOUBLE      NULL AFTER matched_media_id,
    ADD COLUMN matched_at      DATETIME(6) NULL AFTER match_score;
