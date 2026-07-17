package com.similarproducts.app.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProductIdTest {

	@Test
	void rejectsBlankValue() {
		assertThatThrownBy(() -> new ProductId("   "))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsEmptyValue() {
		assertThatThrownBy(() -> new ProductId(""))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNullValue() {
		assertThatThrownBy(() -> new ProductId(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void acceptsNonBlankValue() {
		ProductId productId = new ProductId("p1");

		assertThat(productId.value()).isEqualTo("p1");
	}
}
