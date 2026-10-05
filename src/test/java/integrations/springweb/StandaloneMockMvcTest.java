package integrations.springweb;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc with no Spring Boot and no application context at all: {@code standaloneSetup} builds
 * just enough MVC infrastructure around controller instances you construct yourself. It starts in
 * milliseconds and needs no mocks for what you would rather use for real (the catalog here), but
 * nothing is found for you: an advice you do not register does not exist, and neither does any
 * Boot customization of Jackson or MVC.
 */
class StandaloneMockMvcTest {

    private static final String JCIP = """
            {"isbn":"9780321349606","title":"Java Concurrency in Practice","author":"Brian Goetz","year":2006}""";

    private final BookController controller = new BookController(new BookCatalog(), mock(PricingService.class));

    /** No context to ask for the optional registry, so hand the advice an empty provider. */
    private static ApiExceptionHandler advice() {
        return new ApiExceptionHandler(new StaticListableBeanFactory().getBeanProvider(CircuitBreakerRegistry.class));
    }

    @Test
    void aRoundTripThroughARealCatalog() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice())
                .build();

        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content(JCIP))
                .andExpect(status().isCreated());
        mvc.perform(get("/books/9780321349606"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.author").value("Brian Goetz"));
        mvc.perform(delete("/books/9780321349606"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/books/9780321349606"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Book not found"));
    }

    @Test
    void validationStillRunsIfAValidatorIsOnTheClasspath() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice())
                .build();

        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"isbn":"1","title":"t","author":"a","year":1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.isbn").value("must be 13 digits"));
    }

    @Test
    void withoutTheAdviceTheApplicationsExceptionEscapes() {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        ServletException thrown = assertThrows(ServletException.class, () -> mvc.perform(get("/books/9780321349606")));
        assertInstanceOf(BookNotFoundException.class, thrown.getRootCause());
    }
}
