package com.similarproducts.app.application;

import com.similarproducts.app.domain.exception.RootProductNotFoundException;
import com.similarproducts.app.domain.model.ProductDetail;
import com.similarproducts.app.domain.model.ProductId;
import com.similarproducts.app.domain.port.in.GetSimilarProductsUseCase;
import com.similarproducts.app.domain.port.out.ProductDetailPort;
import com.similarproducts.app.domain.port.out.SimilarIdsPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Orchestrates similar product retrieval: resolves similar ids for a root
 * product, then fetches each id's detail, dropping any per-id failure while
 * preserving similarity order.
 *
 * <p>Not yet annotated as a Spring bean: {@link SimilarIdsPort} and
 * {@link ProductDetailPort} have no adapter implementations until the
 * outbound infrastructure phase, so eager component scanning would fail to
 * autowire this class. Wiring (via {@code @Service} or explicit
 * {@code @Bean}) is deferred to that phase.
 */
public class GetSimilarProductsService implements GetSimilarProductsUseCase {

	private final SimilarIdsPort similarIdsPort;
	private final ProductDetailPort productDetailPort;

	public GetSimilarProductsService(SimilarIdsPort similarIdsPort, ProductDetailPort productDetailPort) {
		this.similarIdsPort = similarIdsPort;
		this.productDetailPort = productDetailPort;
	}

	@Override
	public Flux<ProductDetail> getSimilarProducts(ProductId rootId) {
		return similarIdsPort.getSimilarIds(rootId)
			.onErrorMap(e -> new RootProductNotFoundException("Root product not found: " + rootId.value(), e))
			.flatMapSequential(id -> productDetailPort.getProductDetail(id)
				.onErrorResume(e -> Mono.empty()));
	}
}
