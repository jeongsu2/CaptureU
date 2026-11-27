-- 참고 계정 결과를 공유 캐시에서 가져왔는지 (#9). 캐시 적용 전후 외부 호출 수·시간 비교에 쓴다.
ALTER TABLE analysis_reference ADD COLUMN cache_hit BIT(1) NOT NULL DEFAULT b'0' AFTER duration_ms;
