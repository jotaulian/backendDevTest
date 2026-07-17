package com.similarproducts.app.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;

import com.similarproducts.app.infrastructure.adapter.in.web.dto.ProductDetailResponse;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Full-slice integration and NFR verification for {@code GET /product/{id}/similar}.
 *
 * <p>Replaces the real {@code simulado} docker-compose mock (defined by
 * {@code shared/simulado/mocks.json}) with an embedded {@link MockWebServer} that
 * replicates its exact fixtures, so the whole reactive stack (controller ->
 * application service -> both outbound adapters -> resilience4j + Caffeine) is
 * exercised end-to-end without requiring a running docker stack in CI.
 *
 * <p>Per the design's 2s {@code productDetail} TimeLimiter, a "slow" fixture id
 * (5s or 50s server-side delay) is cut off by the client at ~2s wall-clock, not
 * the full server-side delay — {@code cancelRunningFuture=true} cancels the
 * in-flight subscription (and the underlying upstream call) at the timeout, so
 * these scenarios resolve in ~2s, not 5s/50s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "10s")
class SimilarProductsIntegrationTest {

	private static final Map<String, List<String>> SIMILAR_IDS = Map.of(
		"1", List.of("2", "3", "4"),
		"2", List.of("3", "100", "1000"),
		"3", List.of("100", "1000", "10000"),
		"4", List.of("1", "2", "5"),
		"5", List.of("1", "2", "6"));

	/** Counts real upstream hits per product id (similarids calls are not counted). */
	private static final Map<String, AtomicInteger> PRODUCT_DETAIL_HITS = new ConcurrentHashMap<>();

	private static final MockWebServer UPSTREAM = new MockWebServer();

	static {
		UPSTREAM.setDispatcher(upstreamDispatcher());
		try {
			UPSTREAM.start();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@DynamicPropertySource
	static void overrideUpstreamBaseUrl(DynamicPropertyRegistry registry) {
		registry.add("upstream.base-url", () -> "http://localhost:" + UPSTREAM.getPort());
	}

	@AfterAll
	static void shutdownUpstream() {
		try {
			UPSTREAM.shutdown();
		}
		catch (IOException e) {
			// MockWebServer's internal dispatch executor can still have a background thread
			// sleeping out the full server-side setBodyDelay (5s/50s fixtures for ids 1000 and
			// 10000) even though the client already cancelled the request at the 2s TimeLimiter
			// cutoff -- the client cancellation stops OUR wait, not the server's write thread.
			// Shutdown then times out waiting for that thread and throws; harmless here since
			// this is the last use of this server in the JVM, and the daemon thread is reclaimed
			// when the test process exits.
		}
	}

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	private CircuitBreakerRegistry circuitBreakerRegistry;

	@LocalServerPort
	private int localServerPort;

	private WebClient rawWebClient;

	@BeforeEach
	void setUpRawWebClient() {
		rawWebClient = WebClient.builder().baseUrl("http://localhost:" + localServerPort).build();
	}

	// ---- 6.1: 5-scenario sanity ----

	@Test
	void normalScenarioResolvesAllSimilarProductsWhenAllUpstreamCallsAreFast() {
		List<ProductDetailResponse> body = fetchSimilar("1");

		assertThat(body).extracting(ProductDetailResponse::id).containsExactly("2", "3", "4");
	}

	@Test
	void slowScenarioDropsTheProductWhoseDetailCallExceedsTheTwoSecondTimeLimit() {
		List<ProductDetailResponse> body = fetchSimilar("2");

		// id 1000 has a 5s upstream delay, exceeding the 2s TimeLimiter -> dropped.
		assertThat(body).extracting(ProductDetailResponse::id).containsExactly("3", "100");
	}

	@Test
	void notFoundScenarioDropsTheProductWhoseDetailCallReturns404() {
		List<ProductDetailResponse> body = fetchSimilar("4");

		// id 5 returns 404 on its detail call -> dropped.
		assertThat(body).extracting(ProductDetailResponse::id).containsExactly("1", "2");
	}

	@Test
	void errorScenarioDropsTheProductWhoseDetailCallReturns500() {
		List<ProductDetailResponse> body = fetchSimilar("5");

		// id 6 returns 500 on its detail call -> dropped.
		assertThat(body).extracting(ProductDetailResponse::id).containsExactly("1", "2");
	}

	@Test
	void verySlowScenarioDropsBothProductsWhoseDetailCallsExceedTheTwoSecondTimeLimit() {
		List<ProductDetailResponse> body = fetchSimilar("3");

		// ids 1000 (5s) and 10000 (50s) both exceed the 2s TimeLimiter -> both dropped;
		// only id 100 (1s delay) resolves within the limit.
		assertThat(body).extracting(ProductDetailResponse::id).containsExactly("100");
	}

	// ---- 6.2: concurrency ----

	@Test
	void aSlowUpstreamCallDoesNotBlockUnrelatedConcurrentRequests() {
		// root=2 -> [3,100,1000]: id 1000's 5s upstream delay is cut off by the 2s
		// TimeLimiter, so this request takes ~2s wall-clock to resolve.
		CompletableFuture<Long> slowRequest = timedSimilarRequest("2");

		// root=1 -> [2,3,4]: all fast (<=100ms) upstream calls, fired while the slow
		// request above is still in flight.
		List<CompletableFuture<Long>> fastRequests = IntStream.range(0, 3)
			.mapToObj(i -> timedSimilarRequest("1"))
			.toList();

		for (CompletableFuture<Long> fastRequest : fastRequests) {
			long fastElapsedMillis = fastRequest.join();
			assertThat(fastElapsedMillis).isLessThan(500L);
			assertThat(slowRequest.isDone())
				.as("the slow root=2 request must still be pending while unrelated root=1 requests complete")
				.isFalse();
		}

		long slowElapsedMillis = slowRequest.join();
		assertThat(slowElapsedMillis).isBetween(1500L, 3000L);
	}

	// ---- 6.3: sustained upstream failure ----

	@Test
	void sustainedFailuresOnOneUpstreamIdEventuallyFastFailViaCircuitBreakerWithoutDegradingUnrelatedRequests() {
		int hitsBeforeBurst = PRODUCT_DETAIL_HITS.computeIfAbsent("6", key -> new AtomicInteger()).get();

		// root=5 -> [1,2,6]: id 6 always answers 500 immediately (no delay). Repeated calls
		// must eventually trip the dedicated "detail-6" circuit breaker (COUNT_BASED window
		// size=10, minimumNumberOfCalls=5, failureRateThreshold=50%) instead of forever
		// paying an upstream round trip.
		long burstStartNanos = System.nanoTime();
		for (int i = 0; i < 8; i++) {
			List<ProductDetailResponse> body = fetchSimilar("5");
			assertThat(body).extracting(ProductDetailResponse::id).containsExactly("1", "2");
		}
		long burstElapsedMillis = Duration.ofNanos(System.nanoTime() - burstStartNanos).toMillis();

		// 8 sequential calls to an id whose worst-case individual latency is one immediate
		// 500 (no artificial delay in this fixture) must complete quickly in aggregate --
		// no growing/unbounded latency from the repeated failures.
		assertThat(burstElapsedMillis).isLessThan(3000L);

		int hitsAfterBurst = PRODUCT_DETAIL_HITS.get("6").get();
		// Fewer than 8 NEW upstream hits for id 6 proves at least one of the 8 calls was
		// short-circuited by the circuit breaker rather than reaching the upstream every time.
		assertThat(hitsAfterBurst - hitsBeforeBurst).isLessThan(8);

		CircuitBreaker detailSixCircuitBreaker = circuitBreakerRegistry.circuitBreaker("detail-6", "productDetail");
		assertThat(detailSixCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

		// Unrelated concurrent requests (root=1, no dependency on id 6) must not be degraded
		// by the ongoing failures against id 6.
		CompletableFuture<Long> unrelatedFastRequest = timedSimilarRequest("1");
		List<CompletableFuture<Long>> concurrentFailingRequests = IntStream.range(0, 3)
			.mapToObj(i -> timedSimilarRequest("5"))
			.toList();

		assertThat(unrelatedFastRequest.join()).isLessThan(500L);
		for (CompletableFuture<Long> concurrentFailingRequest : concurrentFailingRequests) {
			assertThat(concurrentFailingRequest.join()).isLessThan(500L);
		}
	}

	// ---- helpers ----

	private List<ProductDetailResponse> fetchSimilar(String rootId) {
		return webTestClient.get()
			.uri("/product/{id}/similar", rootId)
			.exchange()
			.expectStatus().isOk()
			.expectBodyList(ProductDetailResponse.class)
			.returnResult()
			.getResponseBody();
	}

	private CompletableFuture<Long> timedSimilarRequest(String rootId) {
		long startNanos = System.nanoTime();
		return rawWebClient.get()
			.uri("/product/{id}/similar", rootId)
			.retrieve()
			.bodyToFlux(ProductDetailResponse.class)
			.collectList()
			.map(list -> Duration.ofNanos(System.nanoTime() - startNanos).toMillis())
			.toFuture();
	}

	private static Dispatcher upstreamDispatcher() {
		return new Dispatcher() {

			@Override
			public MockResponse dispatch(RecordedRequest request) {
				String path = request.getPath();
				if (path == null) {
					return new MockResponse().setResponseCode(404);
				}
				if (path.endsWith("/similarids")) {
					return similarIdsResponse(pathSegment(path));
				}
				String id = pathSegment(path);
				PRODUCT_DETAIL_HITS.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
				return productDetailResponse(id);
			}
		};
	}

	/** Both {@code /product/{id}} and {@code /product/{id}/similarids} carry the id at index 2. */
	private static String pathSegment(String path) {
		return path.split("/")[2];
	}

	private static MockResponse similarIdsResponse(String rootId) {
		List<String> ids = SIMILAR_IDS.getOrDefault(rootId, List.of());
		String body = ids.isEmpty() ? "[]" : ids.stream().collect(Collectors.joining("\",\"", "[\"", "\"]"));
		return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
	}

	private static MockResponse productDetailResponse(String id) {
		return switch (id) {
			case "1" -> jsonResponse("{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}", 0);
			case "2" -> jsonResponse("{\"id\":\"2\",\"name\":\"Dress\",\"price\":19.99,\"availability\":true}", 0);
			case "3" -> jsonResponse("{\"id\":\"3\",\"name\":\"Blazer\",\"price\":29.99,\"availability\":false}", 100);
			case "4" -> jsonResponse("{\"id\":\"4\",\"name\":\"Boots\",\"price\":39.99,\"availability\":true}", 0);
			case "5" -> new MockResponse().setResponseCode(404)
				.setBody("{\"message\":\"Product not found\"}")
				.addHeader("Content-Type", "application/json");
			case "6" -> new MockResponse().setResponseCode(500);
			case "100" -> jsonResponse("{\"id\":\"100\",\"name\":\"Trousers\",\"price\":49.99,\"availability\":false}", 1000);
			case "1000" -> jsonResponse("{\"id\":\"1000\",\"name\":\"Coat\",\"price\":89.99,\"availability\":true}", 5000);
			case "10000" -> jsonResponse(
				"{\"id\":\"10000\",\"name\":\"Leather jacket\",\"price\":89.99,\"availability\":true}", 50000);
			default -> new MockResponse().setResponseCode(404);
		};
	}

	private static MockResponse jsonResponse(String body, long delayMillis) {
		MockResponse response = new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
		if (delayMillis > 0) {
			response.setBodyDelay(delayMillis, TimeUnit.MILLISECONDS);
		}
		return response;
	}
}
