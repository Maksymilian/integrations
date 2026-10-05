package integrations.springweb;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The client side of the seam, with no context: {@link MockRestServiceServer} binds to a
 * {@link RestClient.Builder} and replaces its HTTP transport, so every request is checked against
 * an expectation and answered from a script, and {@link MockRestServiceServer#verify} fails if an
 * expected request never came. Unlike the controller tests, here the 5xx is the input.
 */
class HttpPriceClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://prices.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final PriceClient client = new HttpPriceClient(builder.build());

    @Test
    void getsAndDeserializesThePrice() {
        server.expect(requestTo("http://prices.test/prices/9780134685991"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess("""
                        {"isbn":"9780134685991","amount":45.00,"currency":"USD"}""", MediaType.APPLICATION_JSON));

        Price price = client.fetch("9780134685991");

        assertEquals(new BigDecimal("45.00"), price.amount());
        server.verify();
    }

    @Test
    void a5xxIsAnExceptionHereUnlikeWithJavaNetHttpClient() {
        server.expect(requestTo("http://prices.test/prices/9780134685991"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        HttpServerErrorException thrown = assertThrows(HttpServerErrorException.class,
                () -> client.fetch("9780134685991"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, thrown.getStatusCode());
        server.verify();
    }
}
