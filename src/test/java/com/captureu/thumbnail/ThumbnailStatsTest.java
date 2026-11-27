package com.captureu.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.captureu.thumbnail.ThumbnailService.AccountThumbnails;
import com.captureu.thumbnail.ThumbnailService.Feature;
import com.captureu.thumbnail.ThumbnailService.FeatureStat;
import com.captureu.thumbnail.ThumbnailService.Scope;
import com.captureu.thumbnail.ThumbnailService.ThumbnailSignal;
import com.captureu.thumbnail.ThumbnailTags.Composition;
import com.captureu.thumbnail.ThumbnailTags.TextSize;
import com.captureu.thumbnail.ThumbnailTags.Tone;

class ThumbnailStatsTest {

	private static ThumbnailFeature feature(boolean face, TextSize text) {
		return ThumbnailFeature.of("h" + Math.random(),
				new ThumbnailTags(face, face ? 1 : 0, text, "", Tone.BRIGHT, Composition.CENTER_SUBJECT), "m", "v", 1, 1,
				null, Instant.now());
	}

	@Test
	void 아웃라이어와_나머지의_특징_비율을_비교하고_차이가_크면_근거로_본다() {
		List<ThumbnailFeature> top = List.of(feature(true, TextSize.LARGE), feature(true, TextSize.LARGE),
				feature(true, TextSize.NONE), feature(false, TextSize.LARGE));
		List<ThumbnailFeature> rest = List.of(feature(false, TextSize.NONE), feature(true, TextSize.NONE),
				feature(false, TextSize.LARGE), feature(false, TextSize.NONE));

		List<FeatureStat> stats = ThumbnailService.stats(top, rest);

		FeatureStat face = stats.stream().filter(s -> s.feature() == Feature.FACE_CLOSE_UP).findFirst().orElseThrow();
		assertThat(face.outlierRate()).isEqualTo(75.0);
		assertThat(face.otherRate()).isEqualTo(25.0);
		assertThat(face.signal()).isTrue();
		// 밝은 색감은 양쪽 다 100%라 차이가 없다
		assertThat(stats.stream().filter(s -> s.feature() == Feature.TONE_BRIGHT).findFirst().orElseThrow().signal())
			.isFalse();
	}

	@Test
	void 분석한_썸네일이_적으면_근거로_보지_않는다() {
		List<FeatureStat> stats = ThumbnailService.stats(List.of(feature(true, TextSize.LARGE)),
				Collections.nCopies(5, feature(false, TextSize.NONE)));

		assertThat(stats).noneMatch(FeatureStat::signal);
	}

	@Test
	void 같은_범위에서는_근거_계정이_많은_특징이_먼저고_서로_반대되는_특징은_하나만_남긴다() {
		FeatureStat face = new FeatureStat(Feature.FACE_CLOSE_UP, "얼굴 클로즈업", 70, 30, 40, true);
		FeatureStat vivid = new FeatureStat(Feature.TONE_VIVID, "강렬한 색감", 70, 30, 40, true);
		FeatureStat dark = new FeatureStat(Feature.TONE_DARK, "어두운 색감", 70, 30, 40, true);
		FeatureStat noPerson = new FeatureStat(Feature.NO_PERSON, "사람 없음", 70, 30, 40, true);
		FeatureStat large = new FeatureStat(Feature.LARGE_TEXT, "큰 제목 글씨", 70, 30, 40, true);
		FeatureStat any = new FeatureStat(Feature.ANY_TEXT, "글씨 있음", 70, 30, 40, true);

		List<ThumbnailSignal> signals = ThumbnailService.signals(List.of(
				new AccountThumbnails("me", true, 5, 5, List.of(noPerson, dark)),
				new AccountThumbnails("ref.a", false, 5, 5, List.of(face, vivid, large, any)),
				new AccountThumbnails("ref.b", false, 5, 5, List.of(face, vivid, large, any)),
				new AccountThumbnails("ref.c", false, 5, 5, List.of(vivid))), 3);

		// 강렬한 색감(참고 3개)이 얼굴 클로즈업(참고 2개)보다 먼저. 어두운 색감은 색감 그룹이라,
		// 사람 없음은 얼굴 클로즈업과 반대라, 글씨 있음은 큰 제목 글씨와 겹쳐서 빠진다
		assertThat(signals).extracting(ThumbnailSignal::feature)
			.containsExactly(Feature.TONE_VIVID, Feature.FACE_CLOSE_UP, Feature.LARGE_TEXT);
	}

	@Test
	void 참고_계정_2개_이상의_특징은_공통으로_먼저_온다() {
		FeatureStat face = new FeatureStat(Feature.FACE_CLOSE_UP, "얼굴 클로즈업", 70, 30, 40, true);
		FeatureStat text = new FeatureStat(Feature.LARGE_TEXT, "큰 제목 글씨", 80, 20, 60, true);
		List<ThumbnailSignal> signals = ThumbnailService.signals(List.of(
				new AccountThumbnails("me", true, 5, 5, List.of(text)),
				new AccountThumbnails("ref.a", false, 5, 5, List.of(face)),
				new AccountThumbnails("ref.b", false, 5, 5, List.of(face))), 3);

		assertThat(signals).extracting(ThumbnailSignal::feature, ThumbnailSignal::scope)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(Feature.FACE_CLOSE_UP, Scope.COMMON),
					org.assertj.core.groups.Tuple.tuple(Feature.LARGE_TEXT, Scope.OWN));
		assertThat(signals.get(0).evidence())
			.isEqualTo("참고 계정 3개 중 2개(@ref.a 잘된 썸네일 70% vs 나머지 30%, @ref.b 잘된 썸네일 70% vs 나머지 30%)에서 잘된 썸네일의 특징: 얼굴 클로즈업");
	}
}
