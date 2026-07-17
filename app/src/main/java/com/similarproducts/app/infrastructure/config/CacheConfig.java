package com.similarproducts.app.infrastructure.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.similarproducts.app.domain.model.ProductDetail;

/**
 * Configures the two Caffeine {@link AsyncCache} beans used by the outbound
 * adapters. Both caches coalesce concurrent lookups for the same key into a
 * single in-flight future and apply a shorter TTL to negative/empty results
 * so a bad upstream answer is retried sooner than a good one is re-fetched.
 */
@Configuration
public class CacheConfig {

	private static final long MAX_SIZE = 10_000;
	private static final Duration PRESENT_TTL = Duration.ofSeconds(300);
	private static final Duration EMPTY_TTL = Duration.ofSeconds(5);

	@Bean
	public AsyncCache<String, Optional<ProductDetail>> detailCache() {
		return Caffeine.newBuilder()
			.maximumSize(MAX_SIZE)
			.expireAfter(this.<Optional<ProductDetail>>presentOrEmptyExpiry(Optional::isPresent))
			.buildAsync();
	}

	@Bean
	public AsyncCache<String, List<String>> similarIdsCache() {
		return Caffeine.newBuilder()
			.maximumSize(MAX_SIZE)
			.expireAfter(this.<List<String>>presentOrEmptyExpiry(ids -> !ids.isEmpty()))
			.buildAsync();
	}

	private <V> Expiry<String, V> presentOrEmptyExpiry(java.util.function.Predicate<V> isNonEmpty) {
		return new Expiry<>() {

			@Override
			public long expireAfterCreate(String key, V value, long currentTime) {
				return ttlFor(value).toNanos();
			}

			@Override
			public long expireAfterUpdate(String key, V value, long currentTime, long currentDuration) {
				return ttlFor(value).toNanos();
			}

			@Override
			public long expireAfterRead(String key, V value, long currentTime, long currentDuration) {
				return currentDuration;
			}

			private Duration ttlFor(V value) {
				return isNonEmpty.test(value) ? PRESENT_TTL : EMPTY_TTL;
			}
		};
	}
}
