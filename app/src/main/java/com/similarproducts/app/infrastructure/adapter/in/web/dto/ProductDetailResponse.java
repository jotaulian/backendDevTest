package com.similarproducts.app.infrastructure.adapter.in.web.dto;

import java.math.BigDecimal;

import com.similarproducts.app.domain.model.ProductDetail;

/**
 * Web-facing response representation of a similar product, matching the
 * {@code ProductDetail} schema in {@code similarProducts.yaml}. Kept as a
 * distinct type from the domain {@link ProductDetail} record so the wire
 * format can evolve independently of the domain model.
 */
public record ProductDetailResponse(String id, String name, BigDecimal price, boolean availability) {

	public static ProductDetailResponse fromDomain(ProductDetail detail) {
		return new ProductDetailResponse(detail.id(), detail.name(), detail.price(), detail.availability());
	}
}
