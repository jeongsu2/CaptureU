package com.captureu.domain.instagram.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.captureu.domain.instagram.exception.GraphApiErrorType;
import com.captureu.domain.instagram.exception.GraphApiException;

class ReadContextTest {

	@Test
	void 하위_상태는_자기_호출_수를_세면서_상위에도_바로_더한다() throws Exception {
		ReadContext root = new ReadContext();
		ReadContext refA = root.child();
		ReadContext refB = root.child();
		List<Thread> threads = new ArrayList<>();
		for (int t = 0; t < 8; t++) {
			ReadContext target = t % 2 == 0 ? refA : refB;
			threads.add(Thread.ofVirtual().start(() -> {
				for (int i = 0; i < 500; i++) {
					target.beforeCall();
				}
			}));
		}
		for (int i = 0; i < 1000; i++) {
			root.beforeCall();
		}
		for (Thread t : threads) {
			t.join();
		}

		assertThat(refA.callCount()).isEqualTo(2000);
		assertThat(refB.callCount()).isEqualTo(2000);
		assertThat(root.callCount()).isEqualTo(5000);
	}

	@Test
	void 멈춤_신호는_상위와_하위가_공유하고_다음_호출을_막는다() {
		ReadContext root = new ReadContext();
		ReadContext child = root.child();
		GraphApiException cause = new GraphApiException(GraphApiErrorType.TOKEN_INVALID, 400, 190, 464, "x");

		child.abort(cause);

		assertThat(root.isAborted()).isTrue();
		assertThatThrownBy(root::beforeCall).isSameAs(cause);
		assertThatThrownBy(child::beforeCall).isSameAs(cause);
		assertThat(root.callCount()).isZero();
	}

	@Test
	void 처음_원인만_남긴다() {
		ReadContext root = new ReadContext();
		RuntimeException first = new IllegalStateException("first");

		root.abort(first);
		root.abort(new IllegalStateException("second"));

		assertThatThrownBy(root::throwIfAborted).isSameAs(first);
	}
}
