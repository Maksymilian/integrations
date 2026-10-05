# integrations

Drills for third-party libraries and the seams between them, kept apart from the algorithm build so
their dependencies stay off its classpath. A standalone Maven project with the standard layout
(`src/test/java`); like the `jdk` package, everything here is plain JUnit tests (the Spring app
under test included).

```
cd integrations
JAVA_HOME=~/.sdkman/candidates/java/25.0.1-tem mvn test
```

## resilience4j (`integrations.resilience4j`)

- `CircuitBreakerTest`: the CLOSED/OPEN/HALF_OPEN state machine, one call at a time, on a
  hand-moved clock. OPEN lasts until the clock is strictly *after* the wait, not at it.
- `RetryTest`: attempts, which exceptions are retried, retry on result, backoff.
- `RateLimiterAndBulkheadTest`: calls per period vs. calls in flight.
- `TimeLimiterTest`: the caller stops waiting; the work stops only with `cancelRunningFuture`.
- `DecoratorOrderTest`: in `Decorators` the first `with…` is innermost, and the order decides
  what the breaker counts.
- `HttpClientResilienceTest`: all of it around `java.net.http.HttpClient` against a scripted
  local server (`FakeDownstream`). A 5xx is a *result*, so retry and breaker must be told it fails.

## Spring Web MVC (`integrations.springweb`)

A small Spring Boot 4 REST service, a book catalog that calls two other services, prices and
inventory. The retries and breakers are annotations on the controller methods, so they guard the
HTTP endpoints. The inventory bulkhead is a thread-pool one on `InventoryClient`, so it limits every
call to that service, and it makes the inventory path async (`CompletableFuture` all the way up). It is tested at each level Spring offers. Every breaker, retry and bulkhead is configured in `src/test/resources/application.yml`
(resilience4j-spring-boot4). An annotation names an instance, and the aspect looks it up in the
context's `CircuitBreakerRegistry`, so code and tests that ask the registry for that name get the
same breaker. All errors are RFC 9457
`ProblemDetail`s from one `@RestControllerAdvice` (`ApiExceptionHandler`).

- `BookControllerWebMvcTest`: the `@WebMvcTest` slice with classic `MockMvc` and `@MockitoBean`
  collaborators. JSON in and out, `Location`, validation errors per field, and the 400/405/406/415
  Spring MVC raises before the controller runs. An unhandled exception is *thrown at the test*, not
  turned into a 500, and a scanned `@Configuration` is not part of the slice.
- `BookControllerMockMvcTesterTest`: the same slice through `MockMvcTester`, MockMvc's AssertJ API.
- `StandaloneMockMvcTest`: `MockMvcBuilders.standaloneSetup`, with no context at all. It is fast,
  but an advice you don't register doesn't exist.
- `BookApiFullStackTest`: `@SpringBootTest(RANDOM_PORT)`, real Tomcat, called with `RestClient`.
  An unhandled exception becomes Boot's `/error` 500. Nothing rolls back, so each test uses its own data.
- `PriceEndpointResilienceTest`: the slice plus the two auto-configurations the controller's
  `@CircuitBreaker` needs, resilience4j's and Spring's AOP (`@ImportAutoConfiguration`). Without AOP
  the annotation is silently ignored. The test gets the annotation's breaker from the registry by name.
  502 while closed, 503 + `Retry-After` once open, without calling the mock; other names stay
  closed. The registry is a singleton in a *cached* context, so the breaker is reset before each test.
- `HttpPriceClientTest`: `MockRestServiceServer` scripts the downstream for `RestClient`, where a
  5xx is an exception (unlike `java.net.http.HttpClient`).
- `InventoryAnnotationsTest`: `@Retry` and `@CircuitBreaker` on `StockController`, and a
  `THREADPOOL` `@Bulkhead` on `InventoryClient`, in a full `@SpringBootTest` because slices leave
  out the aspects. Only the `@HttpExchange` interface below the client is mocked, so the bulkhead
  stays real. `MockMvcTester` handles the async dispatch. The call runs on the bulkhead's thread:
  one at a time, one queued, and the next is a 429 that the breaker ignores. The fallback belongs
  on `@Retry`, the outer layer, and the breaker counts each attempt. `/in-stock` calls `stock()`
  from inside the controller, which skips the controller's annotations but not the client's.

## CI

`.github/workflows/ci.yml` runs `mvn verify` on JDK 25 for every push to `main` and every pull
request. Any compile error, test failure or test error fails the job, and the surefire reports are
attached to the failed run. `src/test/resources/junit-platform.properties` gives every test a
60-second timeout, so a deadlock fails the build instead of hanging it.
