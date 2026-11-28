package com.captureu.domain.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.captureu.TestcontainersConfiguration;
import com.captureu.domain.instagram.client.ReferenceAccountReader;
import com.captureu.domain.instagram.client.ReferenceAccountReader.ReferenceProfile;
import com.captureu.domain.instagram.exception.GraphApiErrorType;
import com.captureu.domain.instagram.exception.GraphApiException;
import com.captureu.domain.reference.entity.ReferenceAccount;
import com.captureu.domain.reference.exception.ReferenceAccountRejectedException;
import com.captureu.domain.reference.repository.ReferenceAccountRepository;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "captureu.instagram.graph.ig-user-id=100")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferenceAccountIntegrationTest {

	@Autowired
	ReferenceAccountService service;
	@Autowired
	ReferenceAccountRepository repository;
	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ReferenceAccountReader reader;

	@BeforeEach
	void setUp() {
		repository.deleteAll();
		given(reader.readProfile(eq("100"), eq("ref.a"), any()))
			.willReturn(new ReferenceProfile("900", "ref.a", 78958, 1152));
		given(reader.readProfile(eq("100"), eq("ref.b"), any()))
			.willReturn(new ReferenceProfile("901", "ref.b", 140058, 67));
		given(reader.readProfile(eq("100"), eq("personal"), any()))
			.willThrow(new GraphApiException(GraphApiErrorType.TARGET_NOT_FOUND, 400, 110, 2207013, "Invalid user id"));
		given(reader.readProfile(eq("100"), eq("me"), any())).willReturn(new ReferenceProfile("100", "me", 1, 1));
	}

	@Test
	void 조회되는_계정을_3개까지_등록한다() {
		given(reader.readProfile(eq("100"), eq("ref.c"), any()))
			.willReturn(new ReferenceProfile("902", "ref.c", 50000, 300));

		List<ReferenceAccount> saved = service.replace("100", List.of("@Ref.A", "ref.b", "ref.c"));

		assertThat(saved).extracting(ReferenceAccount::getUsername).containsExactly("ref.a", "ref.b", "ref.c");
		assertThat(service.list("100")).extracting(ReferenceAccount::getIgId).containsExactly("900", "901", "902");
	}

	@Test
	void 다시_등록하면_목록을_통째로_바꾼다() {
		service.replace("100", List.of("ref.a", "ref.b"));

		service.replace("100", List.of("ref.b"));

		assertThat(service.list("100")).extracting(ReferenceAccount::getUsername).containsExactly("ref.b");
	}

	@Test
	void 하나라도_조회할_수_없으면_아무것도_바꾸지_않고_이유를_알려준다() {
		service.replace("100", List.of("ref.a"));

		assertThatThrownBy(() -> service.replace("100", List.of("ref.b", "personal")))
			.isInstanceOfSatisfying(ReferenceAccountRejectedException.class, e -> {
				assertThat(e.getRejections()).singleElement().satisfies(r -> {
					assertThat(r.username()).isEqualTo("personal");
					assertThat(r.reason()).isEqualTo("TARGET_NOT_FOUND");
					assertThat(r.message()).contains("개인 계정이거나 없는 아이디");
				});
			});
		assertThat(service.list("100")).extracting(ReferenceAccount::getUsername).containsExactly("ref.a");
	}

	@Test
	void 내_계정이나_4개_이상이나_0개나_형식이_틀린_아이디는_거절한다() {
		assertThatThrownBy(() -> service.replace("100", List.of("me")))
			.isInstanceOfSatisfying(ReferenceAccountRejectedException.class,
					e -> assertThat(e.getRejections().get(0).reason()).isEqualTo("SELF"));
		assertThatThrownBy(() -> service.replace("100", List.of("a", "b", "c", "d")))
			.isInstanceOfSatisfying(ReferenceAccountRejectedException.class,
					e -> assertThat(e.getRejections().get(0).reason()).isEqualTo("TOO_MANY"));
		assertThatThrownBy(() -> service.replace("100", List.of("bad id")))
			.isInstanceOfSatisfying(ReferenceAccountRejectedException.class,
					e -> assertThat(e.getRejections().get(0).reason()).isEqualTo("INVALID_USERNAME"));
		assertThatThrownBy(() -> service.replace("100", List.of()))
			.isInstanceOfSatisfying(ReferenceAccountRejectedException.class,
					e -> assertThat(e.getRejections().get(0).reason()).isEqualTo("TOO_FEW"));
	}

	@Test
	void API_거절_응답에_계정별_이유가_들어간다() throws Exception {
		mockMvc.perform(put("/api/reference-accounts").contentType(MediaType.APPLICATION_JSON)
			.content("{\"usernames\":[\"ref.a\",\"personal\"]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.rejections[0].username").value("personal"))
			.andExpect(jsonPath("$.rejections[0].reason").value("TARGET_NOT_FOUND"));

		mockMvc.perform(put("/api/reference-accounts").contentType(MediaType.APPLICATION_JSON)
			.content("{\"usernames\":[\"ref.a\"]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].username").value("ref.a"))
			.andExpect(jsonPath("$[0].followersCount").value(78958));
	}

	@Test
	void 형식_오류와_개수_초과는_외부_호출_전에_함께_알려준다() {
		assertThatThrownBy(() -> service.replace("100", List.of("bad id", "a", "b", "c", "d")))
			.isInstanceOfSatisfying(ReferenceAccountRejectedException.class,
					e -> assertThat(e.getRejections()).extracting(ReferenceAccountService.ReferenceRejection::reason)
						.containsExactly("INVALID_USERNAME", "TOO_MANY"));
		org.mockito.Mockito.verify(reader, org.mockito.Mockito.never()).readProfile(any(), any(), any());
	}

	@Test
	void 인스타_쪽_장애는_사용자_입력_오류가_아니라_서버_쪽_상태로_응답한다() throws Exception {
		given(reader.readProfile(eq("100"), eq("slow"), any()))
			.willThrow(new GraphApiException(GraphApiErrorType.TEMPORARY, 502, null, null, "bad gateway"));
		given(reader.readProfile(eq("100"), eq("expired"), any()))
			.willThrow(new GraphApiException(GraphApiErrorType.TOKEN_INVALID, 400, 190, 464, "Sessions not allowed"));
		given(reader.readProfile(eq("100"), eq("limited"), any()))
			.willThrow(new GraphApiException(GraphApiErrorType.RATE_LIMITED, 403, 80002, null, "limit"));

		mockMvc.perform(put("/api/reference-accounts").contentType(MediaType.APPLICATION_JSON)
			.content("{\"usernames\":[\"slow\"]}")).andExpect(status().isBadGateway());
		mockMvc.perform(put("/api/reference-accounts").contentType(MediaType.APPLICATION_JSON)
			.content("{\"usernames\":[\"expired\"]}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.errorType").value("TOKEN_INVALID"));
		mockMvc.perform(put("/api/reference-accounts").contentType(MediaType.APPLICATION_JSON)
			.content("{\"usernames\":[\"limited\"]}")).andExpect(status().isTooManyRequests());
	}
}
