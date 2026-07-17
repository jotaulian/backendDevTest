package com.similarproducts.app.infrastructure.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Configures the {@link WebClient} used to reach the upstream product service,
 * backed by a bounded Reactor Netty connection pool so a burst of concurrent
 * requests cannot exhaust system resources.
 */
@Configuration
public class WebClientConfig {

	private static final String CONNECTION_POOL_NAME = "upstream-connection-pool";
	private static final int MAX_CONNECTIONS = 500;
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);

	@Bean
	public WebClient upstreamWebClient(@Value("${upstream.base-url}") String baseUrl) {
		ConnectionProvider connectionProvider = ConnectionProvider.builder(CONNECTION_POOL_NAME)
			.maxConnections(MAX_CONNECTIONS)
			.build();

		HttpClient httpClient = HttpClient.create(connectionProvider)
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis());

		return WebClient.builder()
			.baseUrl(baseUrl)
			.clientConnector(new ReactorClientHttpConnector(httpClient))
			.build();
	}
}
