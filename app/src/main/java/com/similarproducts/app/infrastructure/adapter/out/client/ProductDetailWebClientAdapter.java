package com.similarproducts.app.infrastructure.adapter.out.client;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.similarproducts.app.domain.exception.ProductNotFoundException;
import com.similarproducts.app.domain.exception.UpstreamServerException;
import com.similarproducts.app.domain.model.ProductDetail;
import com.similarproducts.app.domain.model.ProductId;
import com.similarproducts.app.domain.port.out.ProductDetailPort;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

import reactor.core.publisher.Mono;

/**
 * {@link ProductDetailPort} implementation backed by the upstream
 * {@code GET /product/{id}} endpoint.
 *
 * <p>Each fetch is decorated with the shared {@code productDetail} {@link
 * TimeLimiter} (inner) wrapped by a per-id {@link CircuitBreaker} named
 * {@code detail-<id>} (outer, but sharing the same {@code productDetail}
 * config template) so that a timeout is recorded as a circuit breaker
 * failure. A per-id breaker isolates one bad product id from tripping the
 * breaker for unrelated, healthy ids.
 *
 * <p>Results flow through {@code detailCache}: a successful lookup is cached
 * as {@code Optional.of(...)} (long TTL), a 404 is cached as {@code
 * Optional.empty()} (short negative TTL) so a known-missing id is not
 * re-fetched on every request, and a 5xx/timeout is never cached — the
 * per-id circuit breaker, not the cache, is what stops repeated hammering of
 * a known-bad id.
 */
@Component
public class ProductDetailWebClientAdapter implements ProductDetailPort {

	private static final String PRODUCT_DETAIL_PATH = "/product/{id}";
	private static final String RESILIENCE_CONFIG_NAME = "productDetail";
	private static final String CIRCUIT_BREAKER_NAME_PREFIX = "detail-";

	private final WebClient webClient;
	private final TimeLimiter timeLimiter;
	private final CircuitBreakerRegistry circuitBreakerRegistry;
	private final AsyncCache<String, Optional<ProductDetail>> detailCache;

	public ProductDetailWebClientAdapter(WebClient webClient, TimeLimiterRegistry timeLimiterRegistry,
			CircuitBreakerRegistry circuitBreakerRegistry, AsyncCache<String, Optional<ProductDetail>> detailCache) {
		this.webClient = webClient;
		this.timeLimiter = timeLimiterRegistry.timeLimiter(RESILIENCE_CONFIG_NAME, RESILIENCE_CONFIG_NAME);
		this.circuitBreakerRegistry = circuitBreakerRegistry;
		this.detailCache = detailCache;
	}

	@Override
	public Mono<ProductDetail> getProductDetail(ProductId id) {
		String key = id.value();
		return Mono.fromFuture(() -> detailCache.get(key, (k, executor) -> resilientFetch(k).toFuture()))
			// Any error reaching here is a 5xx/timeout/open-circuit failure (404 was already
			// resumed to a normal Optional.empty() value inside resilientFetch). Caffeine
			// eventually evicts an exceptionally-completed future on its own executor, but that
			// happens asynchronously and is not safe to rely on for an immediate retry;
			// invalidate synchronously here — outside the cache's own computeIfAbsent call
			// stack — so the next lookup is guaranteed to miss the cache and hit the circuit
			// breaker/upstream again rather than replaying a stale failed future.
			.doOnError(e -> detailCache.synchronous().invalidate(key))
			.flatMap(cached -> cached.map(Mono::just).orElseGet(Mono::empty));
	}

	private Mono<Optional<ProductDetail>> resilientFetch(String id) {
		CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(
			CIRCUIT_BREAKER_NAME_PREFIX + id, RESILIENCE_CONFIG_NAME);

		return fetchProductDetail(id)
			.transformDeferred(TimeLimiterOperator.of(timeLimiter))
			.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
			.map(Optional::of)
			.onErrorResume(ProductNotFoundException.class, e -> Mono.just(Optional.empty()));
	}

	private Mono<ProductDetail> fetchProductDetail(String id) {
		return webClient.get()
			.uri(PRODUCT_DETAIL_PATH, id)
			.retrieve()
			.onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
				response -> Mono.error(new ProductNotFoundException("Product not found: " + id)))
			.onStatus(HttpStatusCode::is5xxServerError,
				response -> Mono.error(new UpstreamServerException("Upstream server error for product: " + id)))
			.bodyToMono(ProductDetail.class);
	}
}
