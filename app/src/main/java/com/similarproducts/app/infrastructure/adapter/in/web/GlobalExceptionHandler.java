package com.similarproducts.app.infrastructure.adapter.in.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.similarproducts.app.domain.exception.RootProductNotFoundException;

/**
 * Maps domain exceptions to HTTP responses for the inbound web layer, per
 * {@code similarProducts.yaml} (the 404 response defines no body schema).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(RootProductNotFoundException.class)
	public ResponseEntity<Void> handleRootProductNotFound(RootProductNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
	}
}
