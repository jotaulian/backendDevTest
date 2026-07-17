package com.similarproducts.app.domain.port.in;

import com.similarproducts.app.domain.model.ProductDetail;
import com.similarproducts.app.domain.model.ProductId;
import reactor.core.publisher.Flux;

/**
 * Inbound port: retrieves the details of a root product's similar products.
 */
public interface GetSimilarProductsUseCase {

	Flux<ProductDetail> getSimilarProducts(ProductId rootId);
}
