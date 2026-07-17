package com.similarproducts.app.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.similarproducts.app.domain.exception.RootProductNotFoundException;
import com.similarproducts.app.domain.model.ProductDetail;
import com.similarproducts.app.domain.model.ProductId;
import com.similarproducts.app.domain.port.in.GetSimilarProductsUseCase;
import com.similarproducts.app.infrastructure.adapter.in.web.dto.ProductDetailResponse;

import reactor.core.publisher.Flux;

@WebFluxTest(controllers = SimilarProductsController.class)
class SimilarProductsControllerTest {

	@Autowired
	private WebTestClient webTestClient;

	@MockitoBean
	private GetSimilarProductsUseCase getSimilarProductsUseCase;

	@Test
	void returnsOkWithSchemaConformantArrayAndNoDuplicateIds() {
		when(getSimilarProductsUseCase.getSimilarProducts(eq(new ProductId("1")))).thenReturn(Flux.just(
			new ProductDetail("2", "Camiseta", new BigDecimal("19.99"), true),
			new ProductDetail("3", "Pantalon", new BigDecimal("45.50"), false),
			new ProductDetail("4", "Chaqueta", new BigDecimal("89.00"), true)));

		List<ProductDetailResponse> body = webTestClient.get()
			.uri("/product/1/similar")
			.exchange()
			.expectStatus().isOk()
			.expectBodyList(ProductDetailResponse.class)
			.returnResult()
			.getResponseBody();

		assertThat(body).containsExactly(
			new ProductDetailResponse("2", "Camiseta", new BigDecimal("19.99"), true),
			new ProductDetailResponse("3", "Pantalon", new BigDecimal("45.50"), false),
			new ProductDetailResponse("4", "Chaqueta", new BigDecimal("89.00"), true));

		List<String> ids = body.stream().map(ProductDetailResponse::id).collect(Collectors.toList());
		assertThat(ids).doesNotHaveDuplicates();
	}

	@Test
	void returnsNotFoundWhenRootProductCannotBeResolved() {
		when(getSimilarProductsUseCase.getSimilarProducts(eq(new ProductId("999"))))
			.thenReturn(Flux.error(new RootProductNotFoundException("Root product not found: 999")));

		webTestClient.get()
			.uri("/product/999/similar")
			.exchange()
			.expectStatus().isNotFound();
	}

	@Test
	void returnsOkWithEmptyArrayWhenRootHasNoSimilarIds() {
		when(getSimilarProductsUseCase.getSimilarProducts(eq(new ProductId("7")))).thenReturn(Flux.empty());

		webTestClient.get()
			.uri("/product/7/similar")
			.exchange()
			.expectStatus().isOk()
			.expectBodyList(ProductDetailResponse.class)
			.hasSize(0);
	}
}
