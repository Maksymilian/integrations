package integrations.springweb;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every error as an RFC 9457 {@link ProblemDetail}. Extending {@link ResponseEntityExceptionHandler}
 * covers Spring MVC's own exceptions (400, 405, 406, 415, a {@code ResponseStatusException}, ...);
 * the handlers below add the application's. Anything else is not handled here and escapes the
 * {@code DispatcherServlet}: MockMvc rethrows it, a real container forwards to Boot's {@code /error}.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** Absent in the web slices, which leave the resilience4j auto-configuration out. */
    private final ObjectProvider<CircuitBreakerRegistry> breakers;

    ApiExceptionHandler(ObjectProvider<CircuitBreakerRegistry> breakers) {
        this.breakers = breakers;
    }

    @ExceptionHandler
    ProblemDetail bookNotFound(BookNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Book not found");
        problem.setProperty("isbn", e.isbn());
        return problem;
    }

    /** Retry-After is the refusing breaker's own wait in OPEN, since each instance may configure its own. */
    @ExceptionHandler
    ResponseEntity<ProblemDetail> breakerOpen(CallNotPermittedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The " + e.getCausingCircuitBreakerName() + " service is unavailable, try again later");
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        CircuitBreakerRegistry registry = breakers.getIfAvailable();
        if (registry != null) {
            long waitMillis = registry.circuitBreaker(e.getCausingCircuitBreakerName())
                    .getCircuitBreakerConfig()
                    .getWaitIntervalFunctionInOpenState()
                    .apply(1);
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(Duration.ofMillis(waitMillis).toSeconds()));
        }
        return response.body(problem);
    }

    @ExceptionHandler
    ProblemDetail bulkheadFull(BulkheadFullException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail downstreamFailed(RestClientException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "The pricing service failed");
    }

    /** The default says only "Invalid request content."; add which fields, and why. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
