package com.similarproducts.app.domain.port.out;

import com.similarproducts.app.domain.model.ProductId;
import reactor.core.publisher.Flux;

/**
 * Outbound port: resolves the ids of products similar to a given root product.
 * An upstream error or unknown root id must surface so the application layer
 * can map it to a "root product not found" outcome.
 */
public interface SimilarIdsPort {

	Flux<ProductId> getSimilarIds(ProductId rootId);
}
