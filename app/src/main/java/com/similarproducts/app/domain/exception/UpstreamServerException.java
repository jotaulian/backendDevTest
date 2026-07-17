package com.similarproducts.app.domain.exception;

/**
 * Thrown when an upstream call fails with a server error (5xx). Recorded as a
 * circuit breaker failure.
 */
public class UpstreamServerException extends RuntimeException {

	public UpstreamServerException(String message) {
		super(message);
	}

	public UpstreamServerException(String message, Throwable cause) {
		super(message, cause);
	}
}
