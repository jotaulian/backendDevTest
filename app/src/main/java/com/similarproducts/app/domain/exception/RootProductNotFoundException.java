package com.similarproducts.app.domain.exception;

/**
 * Thrown when the root product's similar ids cannot be resolved, meaning the
 * root product itself is considered not found.
 */
public class RootProductNotFoundException extends RuntimeException {

	public RootProductNotFoundException(String message) {
		super(message);
	}

	public RootProductNotFoundException(String message, Throwable cause) {
		super(message, cause);
	}
}
