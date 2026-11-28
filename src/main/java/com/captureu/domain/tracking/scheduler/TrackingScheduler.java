package com.captureu.domain.tracking.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.captureu.domain.tracking.service.TrackingService;
import com.captureu.domain.tracking.service.TrackingService.CollectResult;

/** 추적을 켠 계정의 게시물을 주기적으로 잰다 (#5). 수집 시점의 허용 시간(최소 30분)보다 자주 돌아야 한다. */
@Component
@ConditionalOnProperty(name = "captureu.tracking.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class TrackingScheduler {

	private static final Logger log = LoggerFactory.getLogger(TrackingScheduler.class);

	private final TrackingService trackingService;

	public TrackingScheduler(TrackingService trackingService) {
		this.trackingService = trackingService;
	}

	@Scheduled(initialDelayString = "${captureu.tracking.scheduler.initial-delay:1m}",
			fixedDelayString = "${captureu.tracking.scheduler.interval:10m}")
	public void collect() {
		try {
			for (CollectResult r : trackingService.collectAll()) {
				log.info("추적 수집 recent={} saved={} failed={} apiCalls={}", r.recent(), r.saved(), r.failed(),
						r.apiCalls());
			}
		}
		catch (RuntimeException e) {
			// 토큰 만료 등. 다음 주기에 다시 시도한다
			log.warn("추적 수집 실패 error={}", e.getClass().getSimpleName());
		}
	}
}
