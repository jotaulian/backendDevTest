package com.similarproducts.app.domain.exception;

/**
 * Thrown when a product detail lookup returns 404. Ignored by the circuit
 * breaker so a legitimate "not found" never trips it.
 */
public class ProductNotFoundException extends RuntimeException {

	public ProductNotFoundException(String message) {
		super(message);
	}

	public ProductNotFoundException(String message, Throwable cause) {
		super(message, cause);
	}
}
