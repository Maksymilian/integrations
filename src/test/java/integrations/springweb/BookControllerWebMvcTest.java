package integrations.springweb;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web slice: {@code @WebMvcTest} starts only the MVC layer (controllers, advice, converters,
 * Jackson, validation) and a {@link MockMvc} that pushes requests through the real
 * {@code DispatcherServlet} with no server and no socket. Services are not scanned, so the
 * controller's collaborators are {@code @MockitoBean}s, and the tests are about HTTP: mapping,
 * (de)serialization, status codes, headers, validation, and the error body.
 */
@WebMvcTest(BookController.class)
class BookControllerWebMvcTest {

    private static final String ISBN = "9780134685991";
    private static final Book EFFECTIVE_JAVA = new Book(ISBN, "Effective Java", "Joshua Bloch", 2018);
    private static final String EFFECTIVE_JAVA_JSON = """
            {"isbn":"9780134685991","title":"Effective Java","author":"Joshua Bloch","year":2018}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BookCatalog catalog;

    @MockitoBean
    private PricingService pricing;

    // ---------- the happy paths ----------

    @Test
    void getsABookAsJson() throws Exception {
        when(catalog.find(ISBN)).thenReturn(Optional.of(EFFECTIVE_JAVA));

        mvc.perform(get("/books/{isbn}", ISBN).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.title").value("Effective Java"))
                .andExpect(jsonPath("$.year").value(2018));
    }

    @Test
    void listsTheCatalog() throws Exception {
        when(catalog.findAll()).thenReturn(List.of(EFFECTIVE_JAVA, new Book("9780321349606", "JCIP", "Brian Goetz", 2006)));

        mvc.perform(get("/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].author").value("Brian Goetz"));
    }

    @Test
    void postingCreatesAndPointsAtTheNewBook() throws Exception {
        when(catalog.add(any())).thenReturn(true);

        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content(EFFECTIVE_JAVA_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/books/" + ISBN))
                .andExpect(content().json(EFFECTIVE_JAVA_JSON));

        verify(catalog).add(EFFECTIVE_JAVA); // the JSON became an equal record
    }

    // ---------- the application's errors ----------

    @Test
    void anUnknownIsbnIsAProblemDetail() throws Exception {
        when(catalog.find(ISBN)).thenReturn(Optional.empty());

        mvc.perform(get("/books/{isbn}", ISBN).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Book not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.isbn").value(ISBN))
                .andExpect(jsonPath("$.instance").value("/books/" + ISBN));
    }

    @Test
    void aDuplicateIsAConflict() throws Exception {
        when(catalog.add(any())).thenReturn(false);

        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content(EFFECTIVE_JAVA_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already in the catalog")));
    }

    @Test
    void anInvalidBookIs400NamingEachFieldAndNeverReachesTheCatalog() throws Exception {
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"isbn":"123","title":" ","author":"Joshua Bloch","year":-1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.isbn").value("must be 13 digits"))
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.year").exists())
                .andExpect(jsonPath("$.errors.author").doesNotExist());

        verifyNoInteractions(catalog);
    }

    // ---------- what Spring MVC rejects before the controller runs ----------

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("{\"isbn\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Failed to read request"));
        verifyNoInteractions(catalog);
    }

    @Test
    void aBodyThatIsNotJsonIs415() throws Exception {
        mvc.perform(post("/books").contentType(MediaType.TEXT_PLAIN).content("Effective Java"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void askingForXmlIs406() throws Exception {
        mvc.perform(get("/books/{isbn}", ISBN).accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
        verifyNoInteractions(catalog);
    }

    @Test
    void anUnmappedMethodIs405WithAnAllowHeader() throws Exception {
        mvc.perform(put("/books/{isbn}", ISBN).contentType(MediaType.APPLICATION_JSON).content(EFFECTIVE_JAVA_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("DELETE")));
    }

    // ---------- what the slice is and is not ----------

    @Test
    void anUnhandledExceptionIsRethrownNotTurnedInto500() {
        when(pricing.priceOf(ISBN)).thenThrow(new IllegalStateException("bug"));

        // no servlet container, so no error page: MockMvc hands the exception back to the test
        ServletException thrown = assertThrows(ServletException.class,
                () -> mvc.perform(get("/books/{isbn}/price", ISBN)));
        assertEquals("bug", thrown.getRootCause().getMessage());
    }

    @Test
    void theSliceHasNoServicesAndNoUserConfiguration(@Autowired ApplicationContext context) {
        assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(CircuitBreakerRegistry.class),
                "resilience4j's auto-configuration is not on the slice's list");
        assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(PriceClient.class),
                "DownstreamClients is a @Configuration in the scanned package, and the slice filtered it out");
    }
}
