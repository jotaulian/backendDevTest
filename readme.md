# Backend dev technical test
We want to offer a new feature to our customers showing similar products to the one they are currently seeing. To do this we agreed with our front-end applications to create a new REST API operation that will provide them the product detail of the similar products for a given one. [Here](./similarProducts.yaml) is the contract we agreed.

We already have an endpoint that provides the product Ids similar for a given one. We also have another endpoint that returns the product detail by product Id. [Here](./existingApis.yaml) is the documentation of the existing APIs.

**Create a Spring boot application that exposes the agreed REST API on port 5000.**

![Diagram](./assets/diagram.jpg "Diagram")

Note that _Test_ and _Mocks_ components are given, you must only implement _yourApp_.

## Testing and Self-evaluation
You can run the same test we will put through your application. You just need to have docker installed.

First of all, you may need to enable file sharing for the `shared` folder on your docker dashboard -> settings -> resources -> file sharing.

Then you can start the mocks and other needed infrastructure with the following command.
```
docker-compose up -d simulado influxdb grafana
```
Check that mocks are working with a sample request to [http://localhost:3001/product/1/similarids](http://localhost:3001/product/1/similarids).

To execute the test run:
```
docker-compose run --rm k6 run scripts/test.js
```
Browse [http://localhost:3000/d/Le2Ku9NMk/k6-performance-test](http://localhost:3000/d/Le2Ku9NMk/k6-performance-test) to view the results.

## Evaluation
The following topics will be considered:
- Code clarity and maintainability
- Performance
- Resilience

---

## Solution

This fork implements `GET /product/{productId}/similar` on the hexagonal Spring Boot 4.1.0 / Java 21 skeleton scaffolded in `app/`.

### Running the app

Prerequisites: JDK 21, Docker.

1. Start the upstream mock and observability stack:
   ```
   docker-compose up -d simulado influxdb grafana
   ```
2. **macOS only**: port 5000 may be occupied by the AirPlay Receiver. Disable it in System Settings → General → AirDrop & Handoff → AirPlay Receiver, or stop whatever process holds the port.
3. Run the app:
   ```
   cd app
   ./mvnw spring-boot:run
   ```
4. Smoke test:
   ```
   curl http://localhost:5000/product/1/similar
   ```
5. Run the automated test suite:
   ```
   cd app
   ./mvnw test
   ```
   36/36 tests across unit, adapter, web and integration layers.
6. Run the load test (from the repo root, with the app running on :5000):
   ```
   docker-compose run --rm k6 run scripts/test.js
   ```
   Results at http://localhost:3000/d/Le2Ku9NMk/k6-performance-test.

### Development methodology: Spec-Driven Development (SDD) + Strict TDD

Built end-to-end with SDD: every phase produced a reviewable artifact before any code was written, and every artifact was verified against the real source tree afterward rather than trusted at face value.

Phases run, in order:

1. **Explore** — mapped the existing hexagonal skeleton, the two OpenAPI contracts (`similarProducts.yaml`, `existingApis.yaml`), and the k6 load profile before committing to an approach.
2. **Proposal** — scoped the feature: WebFlux orchestration endpoint, root-404 semantics, partial-failure handling. Numeric resilience/cache values were deferred to design.
3. **Spec** — formal requirements and scenarios (all-resolve, root-not-found, timeout-drop, 404-drop, 500-drop, empty-list, concurrency, sustained-failure) derived from `similarProducts.yaml` and the k6 script's 5 scenarios.
4. **Design** — technical approach: reactive `flatMapSequential` orchestration, resilience configuration (2s TimeLimiter, per-product CircuitBreaker), Caffeine `AsyncCache` with negative caching, operator ordering.
5. **Tasks** — broken into 6 implementation phases, each ending in its own reviewable commit.
6. **Apply** (Strict TDD — JUnit 5 + AssertJ + Mockito + Reactor StepVerifier + MockWebServer + WebTestClient), 6 phases:
   - Phase 1 — Domain model, ports, exceptions (`f263f36`)
   - Phase 2 — Application orchestration service (`6c0a597`)
   - Phase 3 — Outbound config + similar-ids adapter (`937e422`)
   - Phase 4 — Resilient product-detail adapter with caching (`9c44c96`)
   - Phase 5 — Inbound web layer + error mapping (`0dd75b5`)
   - Phase 6 — Integration tests: 5-scenario end-to-end, concurrency, sustained-failure/circuit-breaker (`bb4ba7c`)
7. **Verify** — independently re-ran `./mvnw test`/`compile`, cross-checked all 39 tasks and every spec requirement against the actual source (not just the claimed status), diffed the design's numeric configuration against `application.yml` byte-for-byte. Verdict: **PASS**, 0 CRITICAL/WARNING, 1 non-blocking SUGGESTION.
8. **Manual + load verification** — started the app, curl-tested all 5 spec scenarios live, then ran the full k6 suite (200 VUs × 5 scenarios, ~13.3k requests) against the running instance. 0 failures; the per-product circuit breaker was observed opening under sustained failure exactly as designed.

Result: 36/36 automated tests green, every spec scenario manually confirmed against the running app, load test clean.

### Key decisions

- **Root 404 via `/similarids` only.** Root existence is judged solely by the `/product/{id}/similarids` call. No separate `GET /product/{rootId}` call is made — the root's own detail is never fetched or returned. This is the only interpretation consistent with the mock data (product 5's detail 404s but its similarids call succeeds) and the k6 `error` scenario.
- **Partial failure = drop-and-continue.** If a similar product's detail fetch fails (timeout, 404, 500, or open circuit), it is excluded from the response — the request never returns a 5xx because of one bad similar product. Matches `minItems: 0` on the response schema and is the only reading under which the k6 `notFound`/`error` scenarios make sense.
- **Reactive orchestration**, not imperative fan-out: `flatMapSequential` over similar ids, preserving similarity order while running the detail fetches concurrently — required to stay non-blocking under 200 concurrent VUs.
- **2-second per-call `TimeLimiter`** with `cancelRunningFuture=true`, so a 5s/50s mocked upstream delay never stalls a request past 2s.
- **Circuit breaker per product id** (`detail-{id}`), not global — one consistently-failing similar product must not degrade requests for unrelated products. `COUNT_BASED`, window 10, min-calls 5, failure-rate 50%, 10s open wait, 3 half-open calls.
- **Programmatic Resilience4j decoration** (`TimeLimiterOperator` inner, `CircuitBreakerOperator` outer, via `transformDeferred`) instead of annotations — annotations don't compose cleanly with a dynamic, per-id circuit breaker on a `Mono`.
- **Caffeine `AsyncCache`, not `@Cacheable`** — needed a custom `Expiry` for asymmetric TTLs: 300s for successful lookups, 5s negative-cache for 404s only. 500s and timeouts are never cached (`doOnError` invalidates instead), so the circuit breaker — not the cache — is what protects against sustained failures.
- **No `ProductDetailClientDto`.** The upstream `GET /product/{id}` response is byte-identical in shape to the domain `ProductDetail` record, so the outbound adapter deserializes directly into it — one less mapping layer, a documented and verified deviation from the original design.
- **No manual `ResilienceConfig`.** `resilience4j-spring-boot4` auto-configures the `CircuitBreakerRegistry`/`TimeLimiterRegistry` beans directly from `application.yml`; there is no hand-built registry code.
