package com.similarproducts.app.application;

import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.similarproducts.app.domain.exception.RootProductNotFoundException;
import com.similarproducts.app.domain.model.ProductDetail;
import com.similarproducts.app.domain.model.ProductId;
import com.similarproducts.app.domain.port.out.ProductDetailPort;
import com.similarproducts.app.domain.port.out.SimilarIdsPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GetSimilarProductsServiceTest {

	@Mock
	private SimilarIdsPort similarIdsPort;

	@Mock
	private ProductDetailPort productDetailPort;

	private GetSimilarProductsService service() {
		return new GetSimilarProductsService(similarIdsPort, productDetailPort);
	}

	@Test
	void returnsResolvedDetailsInSimilarityOrderWhenAllResolve() {
		ProductId root = new ProductId("root1");
		ProductId id2 = new ProductId("p2");
		ProductId id3 = new ProductId("p3");
		ProductDetail detail2 = new ProductDetail("p2", "Product 2", BigDecimal.valueOf(20), true);
		ProductDetail detail3 = new ProductDetail("p3", "Product 3", BigDecimal.valueOf(30), false);

		when(similarIdsPort.getSimilarIds(root)).thenReturn(Flux.just(id2, id3));
		when(productDetailPort.getProductDetail(id2)).thenReturn(Mono.just(detail2));
		when(productDetailPort.getProductDetail(id3)).thenReturn(Mono.just(detail3));

		StepVerifier.create(service().getSimilarProducts(root))
			.expectNext(detail2)
			.expectNext(detail3)
			.verifyComplete();
	}

	@Test
	void propagatesRootProductNotFoundWhenSimilarIdsFailsAndNeverCallsDetailPort() {
		ProductId root = new ProductId("unknown-root");

		when(similarIdsPort.getSimilarIds(root))
			.thenReturn(Flux.error(new RuntimeException("upstream 404")));

		StepVerifier.create(service().getSimilarProducts(root))
			.expectError(RootProductNotFoundException.class)
			.verify();

		org.mockito.Mockito.verifyNoInteractions(productDetailPort);
	}

	@Test
	void dropsFailingDetailCallsAndReturnsRemainingResolvedProductsInOrder() {
		ProductId root = new ProductId("root1");
		ProductId ok1 = new ProductId("p1");
		ProductId timesOut = new ProductId("p-timeout");
		ProductId ok2 = new ProductId("p2");
		ProductDetail detail1 = new ProductDetail("p1", "Product 1", BigDecimal.valueOf(10), true);
		ProductDetail detail2 = new ProductDetail("p2", "Product 2", BigDecimal.valueOf(20), true);

		when(similarIdsPort.getSimilarIds(root)).thenReturn(Flux.just(ok1, timesOut, ok2));
		when(productDetailPort.getProductDetail(ok1)).thenReturn(Mono.just(detail1));
		when(productDetailPort.getProductDetail(timesOut))
			.thenReturn(Mono.error(new java.util.concurrent.TimeoutException("timed out")));
		when(productDetailPort.getProductDetail(ok2)).thenReturn(Mono.just(detail2));

		StepVerifier.create(service().getSimilarProducts(root))
			.expectNext(detail1)
			.expectNext(detail2)
			.verifyComplete();
	}

	@Test
	void returnsEmptyFluxWhenSimilarIdsListIsEmpty() {
		ProductId root = new ProductId("root-no-similars");

		when(similarIdsPort.getSimilarIds(root)).thenReturn(Flux.empty());

		StepVerifier.create(service().getSimilarProducts(root))
			.verifyComplete();

		org.mockito.Mockito.verifyNoInteractions(productDetailPort);
	}

	@Test
	void returnsEmptyFluxAsSuccessWhenAllDetailCallsFail() {
		ProductId root = new ProductId("root1");
		ProductId id1 = new ProductId("p1");
		ProductId id2 = new ProductId("p2");

		when(similarIdsPort.getSimilarIds(root)).thenReturn(Flux.just(id1, id2));
		when(productDetailPort.getProductDetail(id1))
			.thenReturn(Mono.error(new RuntimeException("500")));
		when(productDetailPort.getProductDetail(id2))
			.thenReturn(Mono.error(new RuntimeException("404")));

		StepVerifier.create(service().getSimilarProducts(root))
			.verifyComplete();
	}

	@Test
	void preservesSimilarityOrderAcrossMixedSuccessAndFailure() {
		ProductId root = new ProductId("root1");
		ProductId a = new ProductId("a");
		ProductId b = new ProductId("b");
		ProductId c = new ProductId("c");
		ProductId d = new ProductId("d");
		ProductDetail detailA = new ProductDetail("a", "A", BigDecimal.valueOf(1), true);
		ProductDetail detailC = new ProductDetail("c", "C", BigDecimal.valueOf(3), true);
		ProductDetail detailD = new ProductDetail("d", "D", BigDecimal.valueOf(4), true);

		when(similarIdsPort.getSimilarIds(root)).thenReturn(Flux.just(a, b, c, d));
		when(productDetailPort.getProductDetail(a)).thenReturn(Mono.just(detailA));
		when(productDetailPort.getProductDetail(b)).thenReturn(Mono.error(new RuntimeException("boom")));
		when(productDetailPort.getProductDetail(c))
			.thenReturn(Mono.just(detailC).delayElement(Duration.ofMillis(20)));
		when(productDetailPort.getProductDetail(d)).thenReturn(Mono.just(detailD));

		StepVerifier.create(service().getSimilarProducts(root))
			.expectNext(detailA)
			.expectNext(detailC)
			.expectNext(detailD)
			.verifyComplete();
	}
}
