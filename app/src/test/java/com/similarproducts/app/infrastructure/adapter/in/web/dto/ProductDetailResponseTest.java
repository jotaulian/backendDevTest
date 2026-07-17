package com.similarproducts.app.infrastructure.adapter.in.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.similarproducts.app.domain.model.ProductDetail;

class ProductDetailResponseTest {

	@Test
	void mapsAllFourFieldsFromDomainProductDetail() {
		ProductDetail domain = new ProductDetail("2", "Camiseta", new BigDecimal("19.99"), true);

		ProductDetailResponse response = ProductDetailResponse.fromDomain(domain);

		assertThat(response.id()).isEqualTo("2");
		assertThat(response.name()).isEqualTo("Camiseta");
		assertThat(response.price()).isEqualTo(new BigDecimal("19.99"));
		assertThat(response.availability()).isTrue();
	}

	@Test
	void mapsUnavailableProductWithDifferentValues() {
		ProductDetail domain = new ProductDetail("9", "Pantalon", new BigDecimal("45.50"), false);

		ProductDetailResponse response = ProductDetailResponse.fromDomain(domain);

		assertThat(response.id()).isEqualTo("9");
		assertThat(response.name()).isEqualTo("Pantalon");
		assertThat(response.price()).isEqualTo(new BigDecimal("45.50"));
		assertThat(response.availability()).isFalse();
	}
}
