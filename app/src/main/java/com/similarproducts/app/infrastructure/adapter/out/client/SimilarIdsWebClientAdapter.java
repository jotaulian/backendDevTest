package com.similarproducts.app.infrastructure.adapter.out.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.similarproducts.app.domain.model.ProductId;
import com.similarproducts.app.domain.port.out.SimilarIdsPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link SimilarIdsPort} implementation backed by the upstream
 * {@code GET /product/{id}/similarids} endpoint.
 *
 * <p>Lookups go through {@code similarIdsCache}: concurrent requests for the
 * same root id share a single in-flight upstream call (Caffeine's
 * {@code AsyncCache#get} coalescing). Upstream errors (404/5xx/connection
 * failure) are surfaced as-is, not caught here — {@code GetSimilarProductsService}
 * maps them to {@code RootProductNotFoundException} one layer up.
 */
@Component
public class SimilarIdsWebClientAdapter implements SimilarIdsPort {

	private static final String SIMILAR_IDS_PATH = "/product/{id}/similarids";

	private final WebClient webClient;
	private final AsyncCache<String, List<String>> similarIdsCache;

	public SimilarIdsWebClientAdapter(WebClient webClient, AsyncCache<String, List<String>> similarIdsCache) {
		this.webClient = webClient;
		this.similarIdsCache = similarIdsCache;
	}

	@Override
	public Flux<ProductId> getSimilarIds(ProductId rootId) {
		String key = rootId.value();
		return Mono.fromFuture(() -> similarIdsCache.get(key, (id, executor) -> fetchSimilarIds(id)))
			.flatMapIterable(ids -> ids)
			.map(ProductId::new);
	}

	private CompletableFuture<List<String>> fetchSimilarIds(String id) {
		return webClient.get()
			.uri(SIMILAR_IDS_PATH, id)
			.retrieve()
			.bodyToMono(String[].class)
			.map(List::of)
			.toFuture();
	}
}
