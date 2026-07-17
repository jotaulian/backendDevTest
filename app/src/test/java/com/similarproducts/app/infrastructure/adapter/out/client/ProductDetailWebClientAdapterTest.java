package com.similarproducts.app.infrastructure.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.similarproducts.app.domain.exception.ProductNotFoundException;
import com.similarproducts.app.domain.model.ProductDetail;
import com.similarproducts.app.domain.model.ProductId;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import reactor.test.StepVerifier;

class ProductDetailWebClientAdapterTest {

	/** Short timeout for fast unit tests; kept well below the 2s production value. */
	private static final Duration TEST_TIMEOUT = Duration.ofMillis(300);
	private static final String RESILIENCE_CONFIG_NAME = "productDetail";

	private MockWebServer server;
	private WebClient webClient;
	private TimeLimiterRegistry timeLimiterRegistry;
	private CircuitBreakerRegistry circuitBreakerRegistry;
	private AsyncCache<String, Optional<ProductDetail>> detailCache;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
		timeLimiterRegistry = TimeLimiterRegistry.of(Map.of(RESILIENCE_CONFIG_NAME, TimeLimiterConfig.custom()
			.timeoutDuration(TEST_TIMEOUT)
			.cancelRunningFuture(true)
			.build()));
		circuitBreakerRegistry = CircuitBreakerRegistry.of(Map.of(RESILIENCE_CONFIG_NAME, productDetailCbConfig()));
		detailCache = Caffeine.newBuilder().buildAsync();
	}

	@AfterEach
	void tearDown() throws IOException {
		server.close();
	}

	/** Mirrors the production `resilience4j.circuitbreaker.configs.productDetail` block in application.yml. */
	private static CircuitBreakerConfig productDetailCbConfig() {
		return CircuitBreakerConfig.custom()
			.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
			.slidingWindowSize(10)
			.minimumNumberOfCalls(5)
			.failureRateThreshold(50)
			.waitDurationInOpenState(Duration.ofSeconds(10))
			.permittedNumberOfCallsInHalfOpenState(3)
			.recordExceptions(com.similarproducts.app.domain.exception.UpstreamServerException.class, TimeoutException.class)
			.ignoreExceptions(ProductNotFoundException.class)
			.build();
	}

	private ProductDetailWebClientAdapter adapter() {
		return new ProductDetailWebClientAdapter(webClient, timeLimiterRegistry, circuitBreakerRegistry, detailCache);
	}

	@Test
	void returnsProductDetailMappedFromUpstreamResponseWithinTimeLimit() {
		ProductDetailWebClientAdapter adapter = adapter();

		server.enqueue(new MockResponse()
			.setBody("{\"id\":\"1\",\"name\":\"Shirt\",\"price\":9.99,\"availability\":true}")
			.addHeader("Content-Type", "application/json"));

		StepVerifier.create(adapter.getProductDetail(new ProductId("1")))
			.expectNext(new ProductDetail("1", "Shirt", BigDecimal.valueOf(9.99), true))
			.verifyComplete();
	}

	@Test
	void dropsCallThatExceedsTheConfiguredTimeLimit() {
		ProductDetailWebClientAdapter adapter = adapter();

		server.enqueue(new MockResponse()
			.setBody("{\"id\":\"1000\",\"name\":\"Coat\",\"price\":89.99,\"availability\":true}")
			.addHeader("Content-Type", "application/json")
			.setBodyDelay(3, TimeUnit.SECONDS));

		long start = System.nanoTime();
		StepVerifier.create(adapter.getProductDetail(new ProductId("1000")))
			.expectError(TimeoutException.class)
			.verify(Duration.ofSeconds(3));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(elapsed).isLessThan(Duration.ofMillis(2500));
	}

	@Test
	void createsADedicatedPerIdCircuitBreakerFromTheSharedProductDetailTemplate() {
		ProductDetailWebClientAdapter adapter = adapter();

		server.enqueue(new MockResponse()
			.setBody("{\"id\":\"42\",\"name\":\"Hat\",\"price\":5.00,\"availability\":true}")
			.addHeader("Content-Type", "application/json"));

		StepVerifier.create(adapter.getProductDetail(new ProductId("42")))
			.expectNextCount(1)
			.verifyComplete();

		Optional<CircuitBreaker> circuitBreaker = circuitBreakerRegistry.find("detail-42");
		assertThat(circuitBreaker).isPresent();
		assertThat(circuitBreaker.get().getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10);
		assertThat(circuitBreaker.get().getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(5);
	}

	@Test
	void ignores404AsACircuitBreakerFailureSoRepeatedNotFoundNeverOpensIt() {
		ProductDetailWebClientAdapter adapter = adapter();
		ProductId id = new ProductId("5");

		// getProductDetail maps a 404 to a dropped (empty) result, not an error — the negative
		// cache entry is invalidated between calls so each iteration re-hits the upstream and
		// exercises the circuit breaker's ignoreExceptions classification for real.
		for (int i = 0; i < 6; i++) {
			server.enqueue(new MockResponse().setResponseCode(404));
			StepVerifier.create(adapter.getProductDetail(id)).verifyComplete();
			detailCache.synchronous().invalidate(id.value());
		}

		CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("detail-5", RESILIENCE_CONFIG_NAME);
		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(server.getRequestCount()).isEqualTo(6);
	}

	@Test
	void recordsServerErrorsAndTimeoutsAsCircuitBreakerFailuresAndOpensAfterThreshold() {
		ProductDetailWebClientAdapter adapter = adapter();
		ProductId id = new ProductId("6");

		// 5 failures (minimumNumberOfCalls) at 100% failure rate: 2 timeouts + 3 server errors.
		server.enqueue(new MockResponse()
			.setBody("{\"id\":\"6\",\"name\":\"Boots\",\"price\":39.99,\"availability\":true}")
			.addHeader("Content-Type", "application/json")
			.setBodyDelay(1, TimeUnit.SECONDS));
		server.enqueue(new MockResponse()
			.setBody("{\"id\":\"6\",\"name\":\"Boots\",\"price\":39.99,\"availability\":true}")
			.addHeader("Content-Type", "application/json")
			.setBodyDelay(1, TimeUnit.SECONDS));
		server.enqueue(new MockResponse().setResponseCode(500));
		server.enqueue(new MockResponse().setResponseCode(500));
		server.enqueue(new MockResponse().setResponseCode(500));

		for (int i = 0; i < 5; i++) {
			StepVerifier.create(adapter.getProductDetail(id))
				.expectError()
				.verify(Duration.ofSeconds(2));
		}

		CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("detail-6", RESILIENCE_CONFIG_NAME);
		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(server.getRequestCount()).isEqualTo(5);

		// 6th call must fast-fail via the open circuit without reaching the upstream server.
		StepVerifier.create(adapter.getProductDetail(id))
			.expectError(CallNotPermittedException.class)
			.verify(Duration.ofSeconds(1));
		assertThat(server.getRequestCount()).isEqualTo(5);
	}

	@Test
	void cachesA404AsNegativeEmptyResultSoASecondLookupDoesNotCallUpstreamAgain() {
		ProductDetailWebClientAdapter adapter = adapter();
		ProductId id = new ProductId("5");

		server.enqueue(new MockResponse().setResponseCode(404));

		StepVerifier.create(adapter.getProductDetail(id)).verifyComplete();
		StepVerifier.create(adapter.getProductDetail(id)).verifyComplete();

		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void doesNotCacheA500SoASecondLookupCallsUpstreamAgain() {
		ProductDetailWebClientAdapter adapter = adapter();
		ProductId id = new ProductId("6");

		server.enqueue(new MockResponse().setResponseCode(500));
		server.enqueue(new MockResponse().setResponseCode(500));

		StepVerifier.create(adapter.getProductDetail(id)).expectError().verify(Duration.ofSeconds(2));
		StepVerifier.create(adapter.getProductDetail(id)).expectError().verify(Duration.ofSeconds(2));

		assertThat(server.getRequestCount()).isEqualTo(2);
	}

	@Test
	void doesNotCacheATimeoutSoASecondLookupCallsUpstreamAgain() {
		ProductDetailWebClientAdapter adapter = adapter();
		ProductId id = new ProductId("1000");

		server.enqueue(new MockResponse()
			.setBody("{\"id\":\"1000\",\"name\":\"Coat\",\"price\":89.99,\"availability\":true}")
			.addHeader("Content-Type", "application/json")
			.setBodyDelay(3, TimeUnit.SECONDS));
		server.enqueue(new MockResponse()
			.setBody("{\"id\":\"1000\",\"name\":\"Coat\",\"price\":89.99,\"availability\":true}")
			.addHeader("Content-Type", "application/json")
			.setBodyDelay(3, TimeUnit.SECONDS));

		StepVerifier.create(adapter.getProductDetail(id)).expectError(TimeoutException.class).verify(Duration.ofSeconds(2));
		StepVerifier.create(adapter.getProductDetail(id)).expectError(TimeoutException.class).verify(Duration.ofSeconds(2));

		assertThat(server.getRequestCount()).isEqualTo(2);
	}

	@Test
	void endToEndWiringDropsASlowCallWithinTheProductionTwoSecondTimeLimitBeforeTheCircuitBreakerIsEvenConsulted() {
		// Uses the REAL production values from application.yml (2s timeout, 10s wait-in-open,
		// etc.), not the shortened TEST_TIMEOUT — proving the actual configured operator chain
		// (TimeLimiter inner / CircuitBreaker outer) works end-to-end, not just the test double.
		TimeLimiterRegistry productionTimeLimiterRegistry = TimeLimiterRegistry.of(Map.of(RESILIENCE_CONFIG_NAME,
			TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(2)).cancelRunningFuture(true).build()));
		ProductDetailWebClientAdapter adapter = new ProductDetailWebClientAdapter(webClient,
			productionTimeLimiterRegistry, circuitBreakerRegistry, detailCache);

		server.enqueue(new MockResponse()
			.setBody("{\"id\":\"1000\",\"name\":\"Coat\",\"price\":89.99,\"availability\":true}")
			.addHeader("Content-Type", "application/json")
			.setBodyDelay(3, TimeUnit.SECONDS));

		long start = System.nanoTime();
		StepVerifier.create(adapter.getProductDetail(new ProductId("1000")))
			.expectError(TimeoutException.class)
			.verify(Duration.ofSeconds(3));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		// Dropped by the 2s TimeLimiter well before the upstream's 3s delay would have resolved,
		// and the resulting TimeoutException was recorded against (not opening, at 1 failure) the
		// dedicated "detail-1000" breaker rather than escaping unclassified.
		assertThat(elapsed).isBetween(Duration.ofMillis(1900), Duration.ofMillis(2900));
		CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("detail-1000", RESILIENCE_CONFIG_NAME);
		assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
	}
}
