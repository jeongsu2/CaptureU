package com.captureu.domain.analysis.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.captureu.domain.analysis.util.CaptionHook.CallToAction;
import com.captureu.domain.analysis.util.CaptionHook.HookType;

/** 실제 크리에이터 캡션 첫 줄 표본(조금 바꿈)으로 확인한다. */
class CaptionHookTest {

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
			"#광고 152 스트레이트체형의 여름 기본템 모음.zip | 152 스트레이트체형의 여름 기본템 모음.zip | TARGET | true | NONE",
			"1분 컷 핀터레스트 무드 메이콥 🌷~? #광고 | 1분 컷 핀터레스트 무드 메이콥 🌷~? | QUESTION | true | NONE",
			"[저장✅] 컨셉별 핀터걸 코디법.zip | 컨셉별 핀터걸 코디법.zip | LIST | false | SAVE_SHARE",
			"(저장•공유) 여름에도 긴바지 못 잃는 여자들을 위한 영상 | 여름에도 긴바지 못 잃는 여자들을 위한 영상 | TARGET | false | SAVE_SHARE",
			"(저장/공유✅) 학생이라면 지금 당장 사야할 무신사템 3가지 | 학생이라면 지금 당장 사야할 무신사템 3가지 | TARGET | false | SAVE_SHARE",
			"[🔥30%] 신제품인데 시즌오프 ... 1년에 딱 한번 | 신제품인데 시즌오프 ... 1년에 딱 한번 | OTHER | false | PROMOTION",
			"[+5cm] 아무도 모르게 152cm -> 157cm 되는 법 💦 | [+5cm] 아무도 모르게 152cm -> 157cm 되는 법 💦 | HOW_TO | false | NONE",
			"[협찬] 피부 회복 성공루틴 공유 ✌🏻 | 피부 회복 성공루틴 공유 ✌🏻 | HOW_TO | true | NONE",
			"갑자기 여친이 이렇게 인생네컷 찍어오면 어떡할거야 | 갑자기 여친이 이렇게 인생네컷 찍어오면 어떡할거야 | QUESTION | false | NONE",
			"키 150-159 공주님들 모여보세요 | 키 150-159 공주님들 모여보세요 | TARGET | false | NONE",
			"뉴트럴•미지근톤! 봄웜라 인생 섀도우팔레트 top4 | 뉴트럴•미지근톤! 봄웜라 인생 섀도우팔레트 top4 | LIST | false | NONE",
			"제 실패담 들어보실래요 ....? 🥹 | 제 실패담 들어보실래요 ....? 🥹 | QUESTION | false | NONE",
			"저 6개월 만났던 분과 이별했어요.... | 저 6개월 만났던 분과 이별했어요.... | STORY | false | NONE",
			"키작녀 아우터 커밍슌! [정핏 vs 오버핏] | 키작녀 아우터 커밍슌! [정핏 vs 오버핏] | TARGET | false | NONE",
			"주문하신 어른여자 겨쿨 나왔습니다 | 주문하신 어른여자 겨쿨 나왔습니다 | ANNOUNCE | false | NONE",
			"저장하구 할인 빵빵한 극건성 애착템 챙겨가 💚 #9월올영세일 | 저장하구 할인 빵빵한 극건성 애착템 챙겨가 💚 | OTHER | false | SAVE_SHARE",
			": 포토부스 보정이 병X이야 | 포토부스 보정이 병X이야 | OTHER | false | NONE",
			"#광고 @laka.official 대학교 4학년이 말아주는 생기 있는 말간 메이크업 | @laka.official 대학교 4학년이 말아주는 생기 있는 말간 메이크업 | OTHER | true | NONE" })
	void 머리말을_떼고_유형을_분류한다(String firstLine, String hook, HookType type, boolean ad, CallToAction cta) {
		CaptionHook result = CaptionHook.parse(firstLine);

		assertThat(result.hookText()).isEqualTo(hook.strip());
		assertThat(result.type()).isEqualTo(type);
		assertThat(result.adDisclosure()).isEqualTo(ad);
		assertThat(result.callToAction()).isEqualTo(cta);
	}

	@ParameterizedTest
	@CsvSource(nullValues = "null", value = { "null", "'   '", "'#광고'" })
	void 비어_있거나_광고_표시만_있으면_후킹_문장이_없다(String firstLine) {
		CaptionHook result = CaptionHook.parse(firstLine);

		assertThat(result.hookText()).isNull();
		assertThat(result.type()).isEqualTo(HookType.OTHER);
	}
}
