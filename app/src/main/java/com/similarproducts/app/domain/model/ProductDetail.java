package com.similarproducts.app.domain.model;

import java.math.BigDecimal;

/**
 * Full detail of a product returned by the similar products endpoint.
 */
public record ProductDetail(String id, String name, BigDecimal price, boolean availability) {
}
