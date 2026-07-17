package com.similarproducts.app.domain.port.out;

import com.similarproducts.app.domain.model.ProductDetail;
import com.similarproducts.app.domain.model.ProductId;
import reactor.core.publisher.Mono;

/**
 * Outbound port: fetches the detail of a single product. Failures (timeout,
 * 404, 500, or an open circuit breaker) are the application layer's
 * responsibility to drop, not this port's.
 */
public interface ProductDetailPort {

	Mono<ProductDetail> getProductDetail(ProductId id);
}
