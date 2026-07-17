package com.similarproducts.app.infrastructure.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.similarproducts.app.domain.model.ProductId;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class SimilarIdsWebClientAdapterTest {

	private MockWebServer server;
	private WebClient webClient;
	private AsyncCache<String, List<String>> similarIdsCache;
	private SimilarIdsWebClientAdapter adapter;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
		similarIdsCache = Caffeine.newBuilder().buildAsync();
		adapter = new SimilarIdsWebClientAdapter(webClient, similarIdsCache);
	}

	@AfterEach
	void tearDown() throws IOException {
		server.close();
	}

	@Test
	void returnsSimilarProductIdsMappedFromUpstreamResponse() throws InterruptedException {
		server.enqueue(new MockResponse()
			.setBody("[\"2\",\"3\",\"4\"]")
			.addHeader("Content-Type", "application/json"));

		StepVerifier.create(adapter.getSimilarIds(new ProductId("1")))
			.expectNext(new ProductId("2"), new ProductId("3"), new ProductId("4"))
			.verifyComplete();

		RecordedRequest recordedRequest = server.takeRequest();
		assertThat(recordedRequest.getPath()).isEqualTo("/product/1/similarids");
	}

	@Test
	void coalescesConcurrentRequestsForSameRootIdIntoSingleUpstreamCall() {
		server.enqueue(new MockResponse()
			.setBody("[\"2\",\"3\",\"4\"]")
			.addHeader("Content-Type", "application/json")
			.setBodyDelay(200, TimeUnit.MILLISECONDS));

		ProductId rootId = new ProductId("1");
		int concurrentSubscribers = 20;

		Flux<List<ProductId>> concurrentCalls = Flux.range(0, concurrentSubscribers)
			.flatMap(i -> adapter.getSimilarIds(rootId).collectList().subscribeOn(Schedulers.parallel()));

		StepVerifier.create(concurrentCalls)
			.expectNextCount(concurrentSubscribers)
			.verifyComplete();

		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void requestsSeparateUpstreamCallsForDifferentRootIds() {
		server.enqueue(new MockResponse()
			.setBody("[\"2\",\"3\"]")
			.addHeader("Content-Type", "application/json"));
		server.enqueue(new MockResponse()
			.setBody("[\"9\"]")
			.addHeader("Content-Type", "application/json"));

		StepVerifier.create(adapter.getSimilarIds(new ProductId("1")))
			.expectNext(new ProductId("2"), new ProductId("3"))
			.verifyComplete();

		StepVerifier.create(adapter.getSimilarIds(new ProductId("5")))
			.expectNext(new ProductId("9"))
			.verifyComplete();

		assertThat(server.getRequestCount()).isEqualTo(2);
	}

	@Test
	void propagatesUpstreamErrorWithoutSwallowingIt() {
		server.enqueue(new MockResponse().setResponseCode(500));

		StepVerifier.create(adapter.getSimilarIds(new ProductId("6")))
			.expectError()
			.verify(Duration.ofSeconds(5));
	}
}
