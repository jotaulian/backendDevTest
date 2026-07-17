package com.similarproducts.app.infrastructure.adapter.in.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.similarproducts.app.domain.model.ProductId;
import com.similarproducts.app.domain.port.in.GetSimilarProductsUseCase;
import com.similarproducts.app.infrastructure.adapter.in.web.dto.ProductDetailResponse;

import reactor.core.publisher.Flux;

/**
 * Inbound REST adapter implementing {@code GET /product/{productId}/similar}
 * from {@code similarProducts.yaml}.
 */
@RestController
public class SimilarProductsController {

	private final GetSimilarProductsUseCase getSimilarProductsUseCase;

	public SimilarProductsController(GetSimilarProductsUseCase getSimilarProductsUseCase) {
		this.getSimilarProductsUseCase = getSimilarProductsUseCase;
	}

	@GetMapping(path = "/product/{productId}/similar", produces = MediaType.APPLICATION_JSON_VALUE)
	public Flux<ProductDetailResponse> getSimilarProducts(@PathVariable String productId) {
		return getSimilarProductsUseCase.getSimilarProducts(new ProductId(productId))
			.map(ProductDetailResponse::fromDomain);
	}
}
