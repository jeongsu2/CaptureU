-- 캡션 첫 줄을 광고 표시 / 행동 유도 문구 / 진짜 후킹 문장으로 나눠 저장한다 (#12).
-- 기존 게시물은 다음 분석 때 채워진다.
ALTER TABLE ig_media
    ADD COLUMN caption_hook      VARCHAR(500) NULL AFTER caption_first_line,
    ADD COLUMN hook_type         VARCHAR(20)  NULL AFTER caption_hook,
    ADD COLUMN ad_disclosure     BIT(1)       NOT NULL DEFAULT b'0' AFTER hook_type,
    ADD COLUMN call_to_action    VARCHAR(20)  NULL AFTER ad_disclosure;
