package com.similarproducts.app.domain.model;

/**
 * Identifier of a product, guaranteed to be non-blank.
 */
public record ProductId(String value) {

	public ProductId {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("ProductId value must not be blank");
		}
	}
}
